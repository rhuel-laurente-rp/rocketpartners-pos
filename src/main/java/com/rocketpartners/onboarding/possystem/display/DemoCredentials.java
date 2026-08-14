package com.rocketpartners.onboarding.possystem.display;

import java.util.Arrays;

/**
 * The single hardcoded operator / PIN pair the {@link LoginView} checks against.
 *
 * <p><strong>THIS IS NOT AUTHENTICATION.</strong> It is a presentation stand-in so a demo can get
 * past the login screen — there is no user store, no hashing, no salting, no session, and no roles.
 * The credentials are compile-time constants, deliberately visible in source, precisely so nobody
 * mistakes this for something to build on. Any real deployment MUST replace this whole class with a
 * call to head-office identity (accounts are provisioned centrally; a POS terminal has no
 * self-registration). Do not "harden" it in place — a repository with one row, or a hashed PIN in a
 * properties file, would only lend fake credibility to fake auth and invite the next person to trust
 * it. Rip it out and wire the real thing.</p>
 *
 * <p>The PIN is compared as {@code char[]} rather than {@link String} only to match
 * {@link javax.swing.JPasswordField#getPassword()}'s contract — not as a security measure. There is
 * nothing here worth protecting.</p>
 */
public final class DemoCredentials {

    private DemoCredentials() {}

    /** Demo operator id, pre-filled into the login form so a presenter can click straight through. */
    public static final String OPERATOR_ID = "1234";

    /** Demo PIN, pre-filled into the login form. Not secret — see the class Javadoc. */
    public static final String PIN = "0000";

    /**
     * @return {@code true} when the entered id and PIN both match the demo pair exactly. A single
     *         combined check, never "unknown id" vs "wrong PIN" — the caller shows one message for
     *         either miss (correct security posture and simpler).
     */
    public static boolean matches(String operatorId, char[] pin) {
        if (operatorId == null || pin == null) return false;
        return OPERATOR_ID.equals(operatorId) && Arrays.equals(PIN.toCharArray(), pin);
    }
}
