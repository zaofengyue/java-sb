package com.nodesb;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ProcessUtils {

    private ProcessUtils() {
    }

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** 执行命令并丢弃输出，只关心是否成功（对应 Python 的 subprocess.run(..., check=True, stdout=DEVNULL)）。 */
    public static boolean runSilently(List<String> cmd) throws IOException {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            int code = p.waitFor();
            return code == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 执行命令并捕获 stdout/stderr（对应 Python 的 capture_output=True）。 */
    public static Result runCapture(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).start();
        String out;
        String err;
        try {
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            return new Result(code, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted");
        }
    }

    /** 等价于 tar --strip-components=1：直接调用系统 tar 命令解压并剥掉包内第一层目录。
     * Linux 容器/VPS 基本都自带 tar（coreutils 的一部分），不引入额外 Java 依赖。 */
    public static void extractTarGzStripped(String tarPath, String destDir) throws IOException {
        new File(destDir).mkdirs();
        List<String> cmd = List.of("tar", "-xzf", tarPath, "-C", destDir, "--strip-components=1");
        Result r;
        try {
            r = runCapture(cmd);
        } catch (IOException e) {
            throw new IOException("系统缺少 tar 命令，无法解压 sing-box 发行包", e);
        }
        if (!r.ok()) {
            throw new IOException("tar 解压失败: " + r.stderr());
        }
    }

    /** 在后台启动一个长期运行的进程，stdout/stderr 都追加写入指定日志文件。 */
    public static Process spawnBackground(List<String> cmd, File logFile, Map<String, String> envRemove) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        if (envRemove != null) {
            for (String key : envRemove.keySet()) {
                pb.environment().remove(key);
            }
        }
        return pb.start();
    }

    public static void chmodExecutable(File file) {
        file.setExecutable(true, false);
        file.setReadable(true, false);
    }

    /** 尽力杀掉残留的旧进程，忽略失败（对应 Python 的 pkill -f，容器里没有 pkill 也不影响主流程）。 */
    public static void pkillQuietly(String pattern) {
        try {
            runSilently(List.of("pkill", "-f", pattern));
        } catch (IOException ignored) {
        }
    }

    public static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
