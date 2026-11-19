package com.superagent.media;

import com.superagent.core.media.TextTokens;
import com.superagent.core.media.TextTokens.Token;
import com.superagent.core.media.Transcript;
import com.superagent.core.media.TranscriptAligner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines transcripts up against the skeleton by edit distance over tokens (Chinese characters, English words), in
 * overlapping windows so an hour of speech needs no more than a small table at a time.
 *
 * <p>Between and on the skeleton's tokens there are slots (boundary 0, token 0, boundary 1, …). Every token of
 * another version falls into one: onto a skeleton token it matched or replaced, or onto the boundary it was inserted
 * at. A slot where any version differs is disputed; a run of those is one disputed span, owned by the cue its first
 * skeleton token is in.
 */
@Component
public class SubtitleAligner implements TranscriptAligner {

    static final int WINDOW = 600, COMMIT = 450;

    @Override
    public Alignment align(List<Transcript> versions, int skeleton) {
        Transcript sk = versions.get(skeleton);
        List<Token> s = new ArrayList<>();
        List<Integer> cueOf = new ArrayList<>();
        for (int c = 0; c < sk.cues().size(); c++) {
            for (Token t : TextTokens.of(sk.cues().get(c).text())) {
                s.add(t);
                cueOf.add(c);
            }
        }
        int n = s.size(), slots = 2 * n + 1;
        boolean[] diff = new boolean[slots];
        List<List<List<Token>>> bySlot = new ArrayList<>();
        for (int v = 0; v < versions.size(); v++) {
            List<List<Token>> mine = new ArrayList<>(slots);
            for (int k = 0; k < slots; k++) {
                mine.add(new ArrayList<>(1));
            }
            bySlot.add(mine);
            if (v == skeleton) {
                for (int j = 0; j < n; j++) {
                    mine.get(2 * j + 1).add(s.get(j));
                }
                continue;
            }
            List<Token> vt = TextTokens.of(versions.get(v).text());
            int[] slot = slots(s, vt);
            boolean[] matched = new boolean[n];
            for (int k = 0; k < vt.size(); k++) {
                mine.get(slot[k]).add(vt.get(k));
                if (slot[k] % 2 == 1 && vt.get(k).key().equals(s.get(slot[k] / 2).key())) {
                    matched[slot[k] / 2] = true;
                } else if (slot[k] % 2 == 0) {
                    diff[slot[k]] = true;
                }
            }
            for (int j = 0; j < n; j++) {
                diff[2 * j + 1] |= !matched[j];
            }
        }

        List<List<TranscriptAligner.Span>> spans = new ArrayList<>();
        List<List<Token>> agreed = new ArrayList<>();
        for (int c = 0; c < sk.cues().size(); c++) {
            spans.add(new ArrayList<>());
            agreed.add(new ArrayList<>());
        }
        int disputes = 0;
        for (int k = 0; k < slots && !sk.cues().isEmpty(); ) {
            if (!diff[k]) {
                if (k % 2 == 1) {
                    agreed.get(cueOf.get(k / 2)).add(s.get(k / 2));
                }
                k++;
                continue;
            }
            int a = k;
            while (k < slots && diff[k]) {
                k++;
            }
            int b = k - 1;
            int firstToken = a % 2 == 1 ? a / 2 : a / 2 < n && a + 1 <= b ? a / 2 : -1;
            int owner = firstToken >= 0 ? cueOf.get(firstToken) : a / 2 > 0 ? cueOf.get(a / 2 - 1) : n > 0 ? cueOf.get(0) : 0;
            flush(spans.get(owner), agreed.get(owner));
            List<String> texts = new ArrayList<>();
            for (List<List<Token>> mine : bySlot) {
                List<Token> in = new ArrayList<>();
                for (int x = a; x <= b; x++) {
                    in.addAll(mine.get(x));
                }
                texts.add(TextTokens.join(in));
            }
            spans.get(owner).add(new TranscriptAligner.Span("D" + (++disputes), texts.get(skeleton), texts));
        }
        List<TranscriptAligner.AlignedCue> cues = new ArrayList<>();
        for (int c = 0; c < sk.cues().size(); c++) {
            flush(spans.get(c), agreed.get(c));
            Transcript.Cue cue = sk.cues().get(c);
            cues.add(new TranscriptAligner.AlignedCue(c, cue.start(), cue.end(), List.copyOf(spans.get(c))));
        }
        return new Alignment(versions.stream().map(Transcript::model).toList(), cues);
    }

    private static void flush(List<TranscriptAligner.Span> into, List<Token> agreed) {
        if (!agreed.isEmpty()) {
            into.add(TranscriptAligner.Span.agreed(TextTokens.join(agreed)));
            agreed.clear();
        }
    }

    /** For each of {@code v}'s tokens, the slot it falls into against {@code s}. */
    static int[] slots(List<Token> s, List<Token> v) {
        int n = s.size(), m = v.size();
        int[] slot = new int[m];
        int i = 0, j = 0;
        while (j < m) {
            if (i >= n) {
                for (; j < m; j++) {
                    slot[j] = 2 * n; // after the last skeleton token
                }
                break;
            }
            int si = Math.min(n, i + WINDOW);
            boolean last = si == n;
            int vj = last ? m : Math.min(m, j + (int) ((si - i) * (double) (m - j) / (n - i) * 1.3) + 50);
            int a = si - i, b = vj - j;
            int[][] cost = new int[a + 1][b + 1];
            for (int x = 0; x <= a; x++) {
                cost[x][0] = x;
            }
            for (int y = 0; y <= b; y++) {
                cost[0][y] = y;
            }
            for (int x = 1; x <= a; x++) {
                String key = s.get(i + x - 1).key();
                for (int y = 1; y <= b; y++) {
                    int sub = cost[x - 1][y - 1] + (key.equals(v.get(j + y - 1).key()) ? 0 : 1);
                    cost[x][y] = Math.min(sub, Math.min(cost[x - 1][y], cost[x][y - 1]) + 1);
                }
            }
            int ey = b;
            if (!last) { // where the version stands at the window's end is open: the cheapest
                for (int y = 0; y <= b; y++) {
                    if (cost[a][y] < cost[a][ey]) {
                        ey = y;
                    }
                }
            }
            // Back from the end, then forwards: each version token's slot; commit only the first COMMIT skeleton tokens.
            int[] ySlot = new int[b];
            int x = a, y = ey;
            while (x > 0 || y > 0) {
                if (x > 0 && y > 0 && cost[x][y] == cost[x - 1][y - 1]
                        + (s.get(i + x - 1).key().equals(v.get(j + y - 1).key()) ? 0 : 1)) {
                    ySlot[y - 1] = 2 * (i + x - 1) + 1;
                    x--;
                    y--;
                } else if (x > 0 && cost[x][y] == cost[x - 1][y] + 1) {
                    x--;
                } else {
                    ySlot[y - 1] = 2 * (i + x);
                    y--;
                }
            }
            int limit = last ? Integer.MAX_VALUE : 2 * (i + COMMIT);
            int used = 0;
            while (used < ey && ySlot[used] < limit) {
                slot[j + used] = ySlot[used];
                used++;
            }
            j += used;
            i = last ? n : i + COMMIT;
        }
        return slot;
    }
}
