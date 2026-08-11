package com.rocketpartners.onboarding.possystem.display;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lays each child out at an <em>exact</em> fraction of the container's available space along one
 * axis, ignoring every child's preferred size.
 *
 * <p>The POS window is fixed-size and non-resizable, so the shell is proportional, not
 * responsive: a column is "30% of the content width", full stop. {@link java.awt.GridBagLayout}
 * can't express that. Its {@code weightx}/{@code weighty} distribute only the <em>surplus</em>
 * left after each child's preferred size is satisfied, so a basket table with wide content
 * claims more than its share and the split drifts silently with no error. This manager takes a
 * fractional weight per child and divides the available space by weight alone — the moment a
 * child sets a preferred size, nothing changes.</p>
 *
 * <p>Add children with a numeric weight as the constraint:</p>
 * <pre>{@code
 * JPanel row = new JPanel(new ProportionalLayout(ProportionalLayout.HORIZONTAL));
 * row.add(left, 0.30f);
 * row.add(right, 0.70f);
 * }</pre>
 *
 * <p>Weights need not sum to 1 — each child receives {@code weight / totalWeight} of the space.
 * Sizes are computed with a cumulative-edge rounding scheme: child <em>i</em> spans from the
 * rounded edge of the running weight sum before it to the rounded edge after it. Integer child
 * sizes therefore always sum <em>exactly</em> to the available space, and the sub-pixel leftover
 * is spread across the boundaries deterministically rather than piling onto the last child.</p>
 */
public class ProportionalLayout implements LayoutManager2 {

    /** Split the container's width; children run left to right in add order. */
    public static final int HORIZONTAL = 0;
    /** Split the container's height; children run top to bottom in add order. */
    public static final int VERTICAL = 1;

    private final int axis;
    // Insertion-ordered so layout walks children in add order; the container reports the same
    // order from getComponents(), and we key off that for positioning.
    private final Map<Component, Float> weights = new LinkedHashMap<>();

    /**
     * @param axis {@link #HORIZONTAL} or {@link #VERTICAL}
     */
    public ProportionalLayout(int axis) {
        if (axis != HORIZONTAL && axis != VERTICAL) {
            throw new IllegalArgumentException("axis must be HORIZONTAL or VERTICAL");
        }
        this.axis = axis;
    }

    @Override
    public void addLayoutComponent(Component comp, Object constraints) {
        if (!(constraints instanceof Number)) {
            throw new IllegalArgumentException(
                    "ProportionalLayout requires a numeric weight constraint, got: " + constraints);
        }
        float w = ((Number) constraints).floatValue();
        if (w < 0f || Float.isNaN(w)) {
            throw new IllegalArgumentException("weight must be a non-negative number, got: " + w);
        }
        weights.put(comp, w);
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
        throw new UnsupportedOperationException(
                "ProportionalLayout needs a weight: use add(component, Float weight)");
    }

    @Override
    public void removeLayoutComponent(Component comp) {
        weights.remove(comp);
    }

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            Component[] kids = parent.getComponents();
            if (kids.length == 0) return;

            Insets in = parent.getInsets();
            int innerW = parent.getWidth() - in.left - in.right;
            int innerH = parent.getHeight() - in.top - in.bottom;
            int available = axis == HORIZONTAL ? innerW : innerH;

            double total = 0;
            for (Component k : kids) total += weightOf(k);
            if (total <= 0) return;

            // Cumulative-edge rounding: walk the running weight fraction and round each boundary
            // once. Sizes are the gaps between consecutive rounded edges, so they sum exactly to
            // `available` and the rounding residue is distributed across boundaries, not dumped
            // on one end.
            double acc = 0;
            int prevEdge = 0;
            int pos = axis == HORIZONTAL ? in.left : in.top;
            for (Component k : kids) {
                acc += weightOf(k);
                int edge = (int) Math.round(acc / total * available);
                int size = edge - prevEdge;
                prevEdge = edge;
                if (axis == HORIZONTAL) {
                    k.setBounds(pos, in.top, size, innerH);
                } else {
                    k.setBounds(in.left, pos, innerW, size);
                }
                pos += size;
            }
        }
    }

    private float weightOf(Component c) {
        Float w = weights.get(c);
        return w == null ? 0f : w;
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        return aggregateSize(parent, false);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return aggregateSize(parent, true);
    }

    // Preferred/minimum are advisory only — the container is fixed-size, so layoutContainer never
    // consults these. Sum along the axis, max across it, so a parent that does honour them still
    // gets a sane request.
    private Dimension aggregateSize(Container parent, boolean min) {
        synchronized (parent.getTreeLock()) {
            Insets in = parent.getInsets();
            int along = 0;
            int across = 0;
            for (Component k : parent.getComponents()) {
                Dimension d = min ? k.getMinimumSize() : k.getPreferredSize();
                if (axis == HORIZONTAL) {
                    along += d.width;
                    across = Math.max(across, d.height);
                } else {
                    along += d.height;
                    across = Math.max(across, d.width);
                }
            }
            int w = axis == HORIZONTAL ? along : across;
            int h = axis == HORIZONTAL ? across : along;
            return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
        }
    }

    @Override
    public Dimension maximumLayoutSize(Container target) {
        return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public float getLayoutAlignmentX(Container target) {
        return 0.5f;
    }

    @Override
    public float getLayoutAlignmentY(Container target) {
        return 0.5f;
    }

    @Override
    public void invalidateLayout(Container target) {
        // Nothing cached — sizes are recomputed from weights on every layoutContainer call.
    }
}
