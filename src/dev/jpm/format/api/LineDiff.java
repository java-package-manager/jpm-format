package dev.jpm.format.api;

import java.util.ArrayList;
import java.util.List;

/** A line diff, for the built-in {@link Formatter#check}. */
final class LineDiff {
    /** {@code [start, end)} of the old text, made of whole lines, becomes {@code replacement}. */
    record Hunk(int start, int end, String replacement) {}

    private LineDiff() {
    }

    /** Above this many changed lines (old times new) the middle is reported as one hunk. */
    private static final long MAX_CELLS = 4_000_000;

    static List<Hunk> hunks(String a, String b) {
        List<Hunk> out = new ArrayList<>();
        if (a.equals(b)) return out;
        List<int[]> la = lines(a), lb = lines(b);
        int n = la.size(), m = lb.size();
        int pre = 0;
        while (pre < n && pre < m && line(a, la.get(pre)).equals(line(b, lb.get(pre)))) pre++;
        int suf = 0;
        while (suf < n - pre && suf < m - pre && line(a, la.get(n - 1 - suf)).equals(line(b, lb.get(m - 1 - suf))))
            suf++;
        int an = n - pre - suf, bn = m - pre - suf;
        if ((long) an * bn > MAX_CELLS) {
            out.add(hunk(a, b, la, lb, pre, pre + an, pre, pre + bn));
            return out;
        }
        int[][] dp = new int[an + 1][bn + 1];
        for (int i = an - 1; i >= 0; i--) {
            for (int j = bn - 1; j >= 0; j--) {
                dp[i][j] = line(a, la.get(pre + i)).equals(line(b, lb.get(pre + j)))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0, j = 0, hi = -1, hj = -1;
        while (i < an || j < bn) {
            if (i < an && j < bn && line(a, la.get(pre + i)).equals(line(b, lb.get(pre + j)))) {
                if (hi >= 0) {
                    out.add(hunk(a, b, la, lb, pre + hi, pre + i, pre + hj, pre + j));
                    hi = hj = -1;
                }
                i++;
                j++;
            } else {
                if (hi < 0) {
                    hi = i;
                    hj = j;
                }
                if (j < bn && (i >= an || dp[i][j + 1] >= dp[i + 1][j])) j++;
                else i++;
            }
        }
        if (hi >= 0) out.add(hunk(a, b, la, lb, pre + hi, pre + an, pre + hj, pre + bn));
        return out;
    }

    private static Hunk hunk(String a, String b, List<int[]> la, List<int[]> lb, int a0, int a1, int b0, int b1) {
        int start = a0 < la.size() ? la.get(a0)[0] : a.length();
        int end = a1 > a0 ? la.get(a1 - 1)[1] : start;
        int bs = b0 < lb.size() ? lb.get(b0)[0] : b.length();
        int be = b1 > b0 ? lb.get(b1 - 1)[1] : bs;
        return new Hunk(start, end, b.substring(bs, be));
    }

    /** Lines as [start, end) including their line break. */
    private static List<int[]> lines(String s) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(new int[] {start, i + 1});
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(new int[] {start, s.length()});
        return out;
    }

    private static String line(String s, int[] r) {
        return s.substring(r[0], r[1]);
    }
}
