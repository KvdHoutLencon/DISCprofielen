package nl.spheredetect;

import java.util.ArrayList;
import java.util.List;

/**
 * Connected component labelling of the change mask, plus grouping of nearby
 * components. Grouping matters because a back-lit sphere shows up as a crescent
 * that can easily break into two or three separate patches; they must be scored
 * as one candidate.
 */
public final class Blobs {

    public static final class Group {
        public int minX, minY, maxX, maxY;
        public int area;
        /** Pixel indices (y*width + x) belonging to this group. */
        public int[] pixels;
        public double centroidX, centroidY;

        public int bboxW() { return maxX - minX + 1; }
        public int bboxH() { return maxY - minY + 1; }
        public int longSide() { return Math.max(bboxW(), bboxH()); }
    }

    private Blobs() {}

    /** 8-connected labelling; components smaller than minArea are dropped. */
    public static List<Group> label(boolean[] mask, int w, int h, int minArea) {
        int[] labels = new int[w * h];
        int[] stack = new int[w * h];
        List<Group> out = new ArrayList<Group>();
        int next = 1;
        int[] buf = new int[w * h];

        for (int start = 0; start < w * h; start++) {
            if (!mask[start] || labels[start] != 0) continue;
            int sp = 0;
            stack[sp++] = start;
            labels[start] = next;
            int n = 0;
            int minX = w, minY = h, maxX = -1, maxY = -1;
            double sx = 0, sy = 0;
            while (sp > 0) {
                int p = stack[--sp];
                int px = p % w, py = p / w;
                buf[n++] = p;
                sx += px; sy += py;
                if (px < minX) minX = px;
                if (px > maxX) maxX = px;
                if (py < minY) minY = py;
                if (py > maxY) maxY = py;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = py + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = px + dx;
                        if (xx < 0 || xx >= w) continue;
                        int q = yy * w + xx;
                        if (mask[q] && labels[q] == 0) {
                            labels[q] = next;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            next++;
            if (n < minArea) continue;
            Group g = new Group();
            g.area = n;
            g.minX = minX; g.minY = minY; g.maxX = maxX; g.maxY = maxY;
            g.centroidX = sx / n;
            g.centroidY = sy / n;
            g.pixels = new int[n];
            System.arraycopy(buf, 0, g.pixels, 0, n);
            out.add(g);
        }
        return out;
    }

    /** Merges components whose bounding boxes come within 'gap' pixels of each other. */
    public static List<Group> merge(List<Group> in, double gap) {
        int n = in.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (near(in.get(i), in.get(j), gap)) union(parent, i, j);
            }
        }
        java.util.HashMap<Integer, List<Group>> members = new java.util.HashMap<Integer, List<Group>>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            List<Group> l = members.get(r);
            if (l == null) { l = new ArrayList<Group>(); members.put(r, l); }
            l.add(in.get(i));
        }
        List<Group> out = new ArrayList<Group>();
        for (List<Group> l : members.values()) {
            if (l.size() == 1) { out.add(l.get(0)); continue; }
            Group g = new Group();
            g.minX = Integer.MAX_VALUE; g.minY = Integer.MAX_VALUE;
            g.maxX = -1; g.maxY = -1;
            int total = 0;
            double sx = 0, sy = 0;
            for (Group m : l) {
                g.minX = Math.min(g.minX, m.minX);
                g.minY = Math.min(g.minY, m.minY);
                g.maxX = Math.max(g.maxX, m.maxX);
                g.maxY = Math.max(g.maxY, m.maxY);
                total += m.area;
                sx += m.centroidX * m.area;
                sy += m.centroidY * m.area;
            }
            g.area = total;
            g.centroidX = sx / total;
            g.centroidY = sy / total;
            g.pixels = new int[total];
            int o = 0;
            for (Group m : l) {
                System.arraycopy(m.pixels, 0, g.pixels, o, m.area);
                o += m.area;
            }
            out.add(g);
        }
        return out;
    }

    private static boolean near(Group a, Group b, double gap) {
        double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
        double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
        return Math.sqrt(dx * dx + dy * dy) <= gap;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[rb] = ra;
    }
}
