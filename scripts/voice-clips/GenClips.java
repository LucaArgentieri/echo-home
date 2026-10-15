import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Renders every word of words.txt with the Piper voice and packs them into one 16-bit PCM file
 * plus an index, so the Echo can speak common answers (time, date, timers) by joining clips
 * instead of running Piper (7-8 s per sentence on its CPU). Run through scripts/gen-voice-clips.sh.
 */
public class GenClips {
    public static void main(String[] a) throws Exception {
        String ttsDir = a[0], wordsFile = a[1], outDir = a[2];
        OfflineTtsVitsModelConfig v = OfflineTtsVitsModelConfig.builder()
            .setModel(ttsDir + "/it_IT-paola-medium.onnx").setTokens(ttsDir + "/tokens.txt")
            .setDataDir(ttsDir + "/espeak-ng-data").build();
        OfflineTtsModelConfig m = OfflineTtsModelConfig.builder().setVits(v).setNumThreads(4).build();
        OfflineTts tts = new OfflineTts(OfflineTtsConfig.builder().setModel(m).build());
        int rate = tts.getSampleRate();

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        StringBuilder index = new StringBuilder("rate " + rate + "\n");
        int offset = 0;
        for (String line : Files.readAllLines(Paths.get(wordsFile))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String key = line, spoken = line;
            int eq = line.indexOf('=');
            if (eq > 0) { key = line.substring(0, eq); spoken = line.substring(eq + 1); }
            float[] s = trim(tts.generate(spoken, 0, 1.0f).getSamples());
            for (float f : s) {
                int x = Math.max(-32768, Math.min(32767, Math.round(f * 32767f)));
                pcm.write(x & 0xff); pcm.write((x >> 8) & 0xff);
            }
            index.append(key).append(' ').append(offset).append(' ').append(s.length).append('\n');
            offset += s.length;
        }
        Files.createDirectories(Paths.get(outDir));
        Files.write(Paths.get(outDir, "clips.pcm"), pcm.toByteArray());
        Files.write(Paths.get(outDir, "index.txt"), index.toString().getBytes("UTF-8"));
        System.out.println("clips: " + offset + " samples, " + pcm.size() / 1024 + " KB");
        tts.release();
    }

    /** Cuts the near-silent padding Piper leaves at both ends so joined words flow. */
    static float[] trim(float[] s) {
        int from = 0, to = s.length;
        while (from < to && Math.abs(s[from]) < 0.01f) from++;
        while (to > from && Math.abs(s[to - 1]) < 0.01f) to--;
        from = Math.max(0, from - 200);
        to = Math.min(s.length, to + 200);
        return Arrays.copyOfRange(s, from, to);
    }
}
