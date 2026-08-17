#!/usr/bin/env bash
#
# Deploy pos-discount-engine to ECS Fargate behind an ALB.
#
# Run from the Mac
#   export AWS_ACCOUNT_ID=123456789012   # your 12-digit account id (required)
#   chmod +x deploy-ecs.sh
#   ./deploy-ecs.sh deploy      # create everything
#   ./deploy-ecs.sh status      # check health
#   ./deploy-ecs.sh redeploy    # after pushing a new image
#   ./deploy-ecs.sh teardown    # delete everything
#
# Safe to re-run: every step checks for an existing resource first.

set -euo pipefail

# Your 12-digit AWS account id. Not hardcoded so this script carries no environment
# details: export it before running, e.g.  export AWS_ACCOUNT_ID=123456789012
# (or derive it: export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text))
ACCOUNT="${AWS_ACCOUNT_ID:?Set AWS_ACCOUNT_ID to your 12-digit AWS account id before running}"
REGION="${AWS_REGION:-us-east-1}"
REPO=pos-discount-engine
ECR_URI=$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$REPO
IMAGE=$ECR_URI:latest

CLUSTER=pos-cluster
SERVICE=pos-engine-svc
FAMILY=pos-discount-engine
CONTAINER=discount-engine
TG_NAME=pos-engine-tg
ALB_NAME=pos-alb
LOG_GROUP=/ecs/pos-discount-engine
CONTAINER_PORT=8080
HEALTH_PATH=/health
ARCH=X86_64          # matches the amd64 image you pushed

STATE=./deploy-state.env
AWS="aws --region $REGION --no-cli-pager"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '    \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '    \033[0;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

save() { grep -v "^export $1=" "$STATE" 2>/dev/null > "$STATE.tmp" || true
         mv "$STATE.tmp" "$STATE" 2>/dev/null || true
         echo "export $1=$2" >> "$STATE"; }

# ---------------------------------------------------------------- preflight

preflight() {
  say "Preflight"

  local who
  who=$($AWS sts get-caller-identity --query Arn --output text) \
    || die "AWS CLI not configured. Run: aws configure"
  ok "Authenticated as $who"

  [[ "$($AWS sts get-caller-identity --query Account --output text)" == "$ACCOUNT" ]] \
    || die "Logged into the wrong AWS account"

  $AWS ecr describe-images --repository-name $REPO --image-ids imageTag=latest \
    --query 'imageDetails[0].imagePushedAt' --output text >/dev/null \
    || die "No :latest tag in ECR. Push the image first."
  ok "Image :latest exists in ECR"

  if command -v docker >/dev/null && docker buildx version >/dev/null 2>&1; then
    if docker buildx imagetools inspect "$IMAGE" 2>/dev/null | grep -q 'linux/arm64'; then
      warn "ECR image reports linux/arm64 — set ARCH=ARM64 at the top of this script,"
      warn "or rebuild with: docker buildx build --platform linux/amd64 --push ..."
      read -rp "    Continue anyway? [y/N] " r; [[ "$r" == y ]] || exit 1
    else
      ok "Image architecture looks like amd64"
    fi
  fi

  touch "$STATE"
}

# ------------------------------------------------------------------ network

discover_vpc() {
  say "Default VPC and subnets"

  VPC=$($AWS ec2 describe-vpcs --filters Name=is-default,Values=true \
        --query 'Vpcs[0].VpcId' --output text)
  [[ "$VPC" != "None" ]] || die "No default VPC in $REGION"
  ok "VPC $VPC"

  # Public subnets only: those whose route table has a route to an internet gateway.
  SUBNET_LIST=()
  for s in $($AWS ec2 describe-subnets --filters Name=vpc-id,Values=$VPC \
             --query 'Subnets[].SubnetId' --output text); do
    local rt
    rt=$($AWS ec2 describe-route-tables \
         --filters Name=association.subnet-id,Values=$s \
         --query 'RouteTables[0].Routes[?starts_with(GatewayId, `igw-`)].GatewayId' \
         --output text 2>/dev/null || true)
    if [[ -z "$rt" ]]; then   # no explicit association -> main route table
      rt=$($AWS ec2 describe-route-tables \
           --filters Name=vpc-id,Values=$VPC Name=association.main,Values=true \
           --query 'RouteTables[0].Routes[?starts_with(GatewayId, `igw-`)].GatewayId' \
           --output text)
    fi
    [[ -n "$rt" ]] && SUBNET_LIST+=("$s")
  done

  (( ${#SUBNET_LIST[@]} >= 2 )) \
    || die "Need >=2 public subnets for an ALB, found ${#SUBNET_LIST[@]}"

  SUBNETS="${SUBNET_LIST[*]}"
  SUBNETS_CSV=$(IFS=,; echo "${SUBNET_LIST[*]}")
  ok "Public subnets: $SUBNETS"

  save VPC "$VPC"; save SUBNETS_CSV "$SUBNETS_CSV"
}

sg_id() { $AWS ec2 describe-security-groups \
            --filters Name=group-name,Values="$1" Name=vpc-id,Values=$VPC \
            --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null; }

security_groups() {
  say "Security groups"

  ALB_SG=$(sg_id pos-alb-sg)
  if [[ "$ALB_SG" == "None" || -z "$ALB_SG" ]]; then
    ALB_SG=$($AWS ec2 create-security-group --vpc-id $VPC \
      --group-name pos-alb-sg --description "POS ALB ingress" \
      --query GroupId --output text)
    $AWS ec2 authorize-security-group-ingress --group-id $ALB_SG \
      --protocol tcp --port 80 --cidr 0.0.0.0/0 >/dev/null
    ok "Created pos-alb-sg $ALB_SG (80 from anywhere)"
  else
    ok "pos-alb-sg exists: $ALB_SG"
  fi

  TASK_SG=$(sg_id pos-task-sg)
  if [[ "$TASK_SG" == "None" || -z "$TASK_SG" ]]; then
    TASK_SG=$($AWS ec2 create-security-group --vpc-id $VPC \
      --group-name pos-task-sg --description "POS Fargate task" \
      --query GroupId --output text)
    # Source is the ALB's security group, not a CIDR: the container is then
    # reachable only through the load balancer, never directly.
    $AWS ec2 authorize-security-group-ingress --group-id $TASK_SG \
      --protocol tcp --port $CONTAINER_PORT --source-group $ALB_SG >/dev/null
    ok "Created pos-task-sg $TASK_SG ($CONTAINER_PORT from ALB only)"
  else
    ok "pos-task-sg exists: $TASK_SG"
  fi

  save ALB_SG "$ALB_SG"; save TASK_SG "$TASK_SG"
}

# --------------------------------------------------------------------- IAM

exec_role() {
  say "Task execution role"

  if $AWS iam get-role --role-name ecsTaskExecutionRole >/dev/null 2>&1; then
    ok "ecsTaskExecutionRole exists"
  else
    $AWS iam create-role --role-name ecsTaskExecutionRole \
      --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{
        "Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},
        "Action":"sts:AssumeRole"}]}' >/dev/null
    $AWS iam attach-role-policy --role-name ecsTaskExecutionRole \
      --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
    ok "Created ecsTaskExecutionRole"
    sleep 10   # IAM is eventually consistent; ECS will reject it if used too soon
  fi
}

# --------------------------------------------------- Step 3: ECS deployment

task_definition() {
  say "Log group and task definition"

  $AWS logs create-log-group --log-group-name $LOG_GROUP 2>/dev/null || true
  $AWS logs put-retention-policy --log-group-name $LOG_GROUP --retention-in-days 7
  ok "Log group $LOG_GROUP (7-day retention)"

  cat > /tmp/taskdef.json <<JSON
{
  "family": "$FAMILY",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "runtimePlatform": { "operatingSystemFamily": "LINUX", "cpuArchitecture": "$ARCH" },
  "executionRoleArn": "arn:aws:iam::$ACCOUNT:role/ecsTaskExecutionRole",
  "containerDefinitions": [{
    "name": "$CONTAINER",
    "image": "$IMAGE",
    "essential": true,
    "portMappings": [{ "containerPort": $CONTAINER_PORT, "protocol": "tcp" }],
    "environment": [
      { "name": "JAVA_TOOL_OPTIONS", "value": "-XX:MaxRAMPercentage=70" }
    ],
    "stopTimeout": 30,
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "$LOG_GROUP",
        "awslogs-region": "$REGION",
        "awslogs-stream-prefix": "ecs"
      }
    }
  }]
}
JSON

  TD=$($AWS ecs register-task-definition --cli-input-json file:///tmp/taskdef.json \
       --query 'taskDefinition.taskDefinitionArn' --output text)
  ok "Registered ${TD##*/}"
  save TD "$TD"
}

cluster() {
  say "Cluster"
  local st
  st=$($AWS ecs describe-clusters --clusters $CLUSTER \
       --query 'clusters[0].status' --output text 2>/dev/null || echo MISSING)
  if [[ "$st" == "ACTIVE" ]]; then
    ok "$CLUSTER already active"
  else
    $AWS ecs create-cluster --cluster-name $CLUSTER >/dev/null
    ok "Created $CLUSTER (Fargate only, no EC2 capacity)"
  fi
}

# ------------------------------------------- Step 4: Application Load Balancer

target_group() {
  say "Target group"

  TG_ARN=$($AWS elbv2 describe-target-groups --names $TG_NAME \
           --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || echo "")

  if [[ -z "$TG_ARN" || "$TG_ARN" == "None" ]]; then
    # target-type ip is mandatory: Fargate uses awsvpc, so each task has its own
    # ENI and there is no instance to register.
    TG_ARN=$($AWS elbv2 create-target-group \
      --name $TG_NAME --protocol HTTP --port $CONTAINER_PORT --vpc-id $VPC \
      --target-type ip \
      --health-check-protocol HTTP --health-check-path $HEALTH_PATH \
      --health-check-interval-seconds 30 --health-check-timeout-seconds 5 \
      --healthy-threshold-count 2 --unhealthy-threshold-count 3 \
      --matcher HttpCode=200 \
      --query 'TargetGroups[0].TargetGroupArn' --output text)
    ok "Created $TG_NAME (health check $HEALTH_PATH)"
  else
    ok "$TG_NAME exists"
  fi

  # Default is 300s, which adds five minutes to every deployment for no benefit.
  $AWS elbv2 modify-target-group-attributes --target-group-arn $TG_ARN \
    --attributes Key=deregistration_delay.timeout_seconds,Value=30 >/dev/null
  save TG_ARN "$TG_ARN"
}

load_balancer() {
  say "Load balancer"

  ALB_ARN=$($AWS elbv2 describe-load-balancers --names $ALB_NAME \
            --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null || echo "")

  if [[ -z "$ALB_ARN" || "$ALB_ARN" == "None" ]]; then
    ALB_ARN=$($AWS elbv2 create-load-balancer --name $ALB_NAME \
      --type application --scheme internet-facing --ip-address-type ipv4 \
      --subnets $SUBNETS --security-groups $ALB_SG \
      --query 'LoadBalancers[0].LoadBalancerArn' --output text)
    ok "Created $ALB_NAME — waiting for it to provision (2-4 min)"
    $AWS elbv2 wait load-balancer-available --load-balancer-arns $ALB_ARN
  else
    ok "$ALB_NAME exists"
  fi

  local listener
  listener=$($AWS elbv2 describe-listeners --load-balancer-arn $ALB_ARN \
             --query 'Listeners[?Port==`80`].ListenerArn' --output text)
  if [[ -z "$listener" ]]; then
    $AWS elbv2 create-listener --load-balancer-arn $ALB_ARN \
      --protocol HTTP --port 80 \
      --default-actions Type=forward,TargetGroupArn=$TG_ARN >/dev/null
    ok "Created HTTP:80 listener -> $TG_NAME"
  else
    ok "HTTP:80 listener exists"
  fi

  ALB_DNS=$($AWS elbv2 describe-load-balancers --load-balancer-arns $ALB_ARN \
            --query 'LoadBalancers[0].DNSName' --output text)
  ok "Endpoint: http://$ALB_DNS"
  save ALB_ARN "$ALB_ARN"; save ALB_DNS "$ALB_DNS"
}

service() {
  say "Service"

  local st
  st=$($AWS ecs describe-services --cluster $CLUSTER --services $SERVICE \
       --query 'services[0].status' --output text 2>/dev/null || echo MISSING)

  if [[ "$st" == "ACTIVE" ]]; then
    ok "$SERVICE exists — updating to the new task definition"
    $AWS ecs update-service --cluster $CLUSTER --service $SERVICE \
      --task-definition $TD --desired-count 1 --force-new-deployment >/dev/null
  else
    [[ "$st" == "INACTIVE" ]] && { warn "Draining a previously deleted service"; sleep 20; }
    $AWS ecs create-service \
      --cluster $CLUSTER --service-name $SERVICE \
      --task-definition $TD --desired-count 1 \
      --launch-type FARGATE --platform-version LATEST \
      --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS_CSV],securityGroups=[$TASK_SG],assignPublicIp=ENABLED}" \
      --load-balancers "targetGroupArn=$TG_ARN,containerName=$CONTAINER,containerPort=$CONTAINER_PORT" \
      --health-check-grace-period-seconds 60 \
      --deployment-configuration "deploymentCircuitBreaker={enable=true,rollback=true},minimumHealthyPercent=100,maximumPercent=200" \
      >/dev/null
    ok "Created $SERVICE"
  fi
}

wait_healthy() {
  say "Waiting for a healthy target (typically 90-150s)"

  for i in $(seq 1 40); do
    local state reason
    state=$($AWS elbv2 describe-target-health --target-group-arn $TG_ARN \
            --query 'TargetHealthDescriptions[0].TargetHealth.State' --output text 2>/dev/null || echo none)
    reason=$($AWS elbv2 describe-target-health --target-group-arn $TG_ARN \
             --query 'TargetHealthDescriptions[0].TargetHealth.Reason' --output text 2>/dev/null || echo "")
    printf '\r    [%02d/40] target: %-12s %s        ' "$i" "$state" "$reason"
    if [[ "$state" == "healthy" ]]; then
      echo; ok "Target healthy"
      say "Verify"
      echo "    curl -i http://$ALB_DNS$HEALTH_PATH"
      curl -s -i --max-time 10 "http://$ALB_DNS$HEALTH_PATH" | head -1 || true
      echo
      echo "    Endpoint:  http://$ALB_DNS"
      echo "    Point POS: ./gradlew runPos --args=\"--discount-engine-url http://$ALB_DNS\""
      return 0
    fi
    sleep 15
  done

  echo; warn "Never went healthy. Diagnostics:"
  status
  return 1
}

# ------------------------------------------------------------- diagnostics

status() {
  [[ -f "$STATE" ]] && source "$STATE"

  say "Service"
  $AWS ecs describe-services --cluster $CLUSTER --services $SERVICE \
    --query 'services[0].{Desired:desiredCount,Running:runningCount,Pending:pendingCount,Rollout:deployments[0].rolloutState}' \
    --output table 2>/dev/null || warn "No service"

  say "Target health"
  $AWS elbv2 describe-target-health --target-group-arn "${TG_ARN:-}" \
    --query 'TargetHealthDescriptions[].{IP:Target.Id,State:TargetHealth.State,Reason:TargetHealth.Reason,Desc:TargetHealth.Description}' \
    --output table 2>/dev/null || warn "No targets"

  say "Recent ECS events (scheduling decisions, in plain English)"
  $AWS ecs describe-services --cluster $CLUSTER --services $SERVICE \
    --query 'services[0].events[:6].message' --output text 2>/dev/null | tr '\t' '\n' || true

  say "Why the last task stopped, if one did"
  local stopped
  stopped=$($AWS ecs list-tasks --cluster $CLUSTER --desired-status STOPPED \
            --query 'taskArns[0]' --output text 2>/dev/null || echo None)
  if [[ "$stopped" != "None" && -n "$stopped" ]]; then
    $AWS ecs describe-tasks --cluster $CLUSTER --tasks "$stopped" \
      --query 'tasks[0].{Stopped:stoppedReason,Container:containers[0].reason,Exit:containers[0].exitCode}' \
      --output table
  else
    ok "No stopped tasks"
  fi

  say "Application logs (last 10 min)"
  $AWS logs tail $LOG_GROUP --since 10m 2>/dev/null | tail -30 || warn "No logs yet"

  [[ -n "${ALB_DNS:-}" ]] && { say "Endpoint"; echo "    http://$ALB_DNS$HEALTH_PATH"; }
}

redeploy() {
  [[ -f "$STATE" ]] && source "$STATE"
  say "Forcing a new deployment (:latest tag is unchanged, so ECS needs telling)"
  $AWS ecs update-service --cluster $CLUSTER --service $SERVICE \
    --force-new-deployment >/dev/null
  $AWS ecs wait services-stable --cluster $CLUSTER --services $SERVICE
  ok "Stable"
  curl -s -o /dev/null -w '    HTTP %{http_code}\n' "http://$ALB_DNS$HEALTH_PATH"
}

teardown() {
  [[ -f "$STATE" ]] && source "$STATE"
  say "Deleting everything (order matters)"
  read -rp "    Delete the ECS service, ALB, target group and cluster? [y/N] " r
  [[ "$r" == y ]] || exit 0

  $AWS ecs update-service --cluster $CLUSTER --service $SERVICE --desired-count 0 >/dev/null 2>&1 || true
  $AWS ecs delete-service --cluster $CLUSTER --service $SERVICE --force >/dev/null 2>&1 || true
  ok "Service deleted"

  if [[ -n "${ALB_ARN:-}" ]]; then
    for l in $($AWS elbv2 describe-listeners --load-balancer-arn "$ALB_ARN" \
               --query 'Listeners[].ListenerArn' --output text 2>/dev/null || true); do
      $AWS elbv2 delete-listener --listener-arn "$l" || true
    done
    $AWS elbv2 delete-load-balancer --load-balancer-arn "$ALB_ARN" || true
    ok "Load balancer deleted (this is the expensive one)"
    sleep 40   # ENIs release asynchronously
  fi

  [[ -n "${TG_ARN:-}" ]] && { $AWS elbv2 delete-target-group --target-group-arn "$TG_ARN" || true; ok "Target group deleted"; }

  $AWS ecs delete-cluster --cluster $CLUSTER >/dev/null 2>&1 || true
  ok "Cluster deleted"

  $AWS logs delete-log-group --log-group-name $LOG_GROUP 2>/dev/null || true
  [[ -n "${TASK_SG:-}" ]] && ($AWS ec2 delete-security-group --group-id "$TASK_SG" 2>/dev/null || warn "task SG still in use, retry in a minute")
  [[ -n "${ALB_SG:-}"  ]] && ($AWS ec2 delete-security-group --group-id "$ALB_SG"  2>/dev/null || warn "ALB SG still in use, retry in a minute")

  say "Still costing money elsewhere?"
  echo "    ECR repo kept (cents/month). Delete with:"
  echo "      aws ecr delete-repository --repository-name $REPO --force --region $REGION"
  echo "    Any standalone EC2 instances you launched are separate and still billing —"
  echo "    check with: aws ec2 describe-instances --region $REGION --query 'Reservations[].Instances[].InstanceId'"
}

case "${1:-deploy}" in
  deploy)
    preflight; discover_vpc; security_groups; exec_role
    task_definition; cluster; target_group; load_balancer; service
    wait_healthy
    ;;
  status)   status ;;
  redeploy) redeploy ;;
  teardown) teardown ;;
  *) echo "Usage: $0 [deploy|status|redeploy|teardown]"; exit 1 ;;
esac