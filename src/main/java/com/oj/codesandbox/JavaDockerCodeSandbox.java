package com.oj.codesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import com.oj.codesandbox.model.ExecuteCodeRequest;
import com.oj.codesandbox.model.ExecuteCodeResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Java Docker 代码沙箱：编译和运行镜像分离（JDK 编译、JRE 运行），容器内完成编译和运行，
 * 具备内存/CPU 限制、禁用网络、内存采样等隔离能力。容器内编译不依赖宿主机安装 JDK
 */
@Component
public class JavaDockerCodeSandbox extends DockerCodeSandboxTemplate {

    /**
     * 运行镜像：openjdk 官方镜像已停止维护并从 Docker Hub 下架，改用 eclipse-temurin；
     * 容器只负责运行已编译的 class（编译在容器内用 JDK 镜像完成），用 JRE 即可
     */
    private static final String IMAGE = "eclipse-temurin:21-jre-alpine";

    /**
     * 编译镜像：JDK 镜像自带 javac，容器内编译用户代码（与运行镜像同为 Temurin 21，产物兼容）
     */
    private static final String COMPILE_IMAGE = "eclipse-temurin:21-jdk-alpine";

    private static final String JAVA_FILE_NAME = "Main.java";

    public static void main(String[] args) {
        JavaDockerCodeSandbox javaNativeCodeSandbox = new JavaDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
//        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        String code = ResourceUtil.readStr("testCode/simpleCompute/Main.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/MemoryError.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/SleepError.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        // 正常路径验证：simpleCompute（stdin 输入）拿到输出；
        // 换成 MemoryError 验证 MLE；换成 SleepError + timeLimit 2000 验证 TLE
        executeCodeRequest.setMemoryLimit(256L);
        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    protected String getImage() {
        return IMAGE;
    }

    @Override
    protected String getCompileImage() {
        return COMPILE_IMAGE;
    }

    @Override
    protected String getCodeFileName() {
        return JAVA_FILE_NAME;
    }

    @Override
    protected String getCompileCmdInContainer() {
        // 编译参数与原生模板一致（UTF-8 编码）
        return "javac -encoding utf-8 " + CONTAINER_CODE_DIR + "/Main.java";
    }

    @Override
    protected String getRunCmdInContainer(long memoryLimitBytes) {
        // Java 堆上限取内存限制的 1.5 倍：让容器 cgroup 作为唯一内存判题口径。
        // 若 -Xmx 小于等于容器限制，JVM 会在堆 commit 未到限制时就提前抛 OOM，
        // 导致统计值远小于限制，且堆需求接近限制的合法程序会被误判 MLE
        String xmxArg = String.format("-Xmx%dm", memoryLimitBytes * 3 / 2 / 1024 / 1024);
        return String.format("java %s -Dfile.encoding=UTF-8 -cp %s Main < %s/%s",
                xmxArg, CONTAINER_CODE_DIR, CONTAINER_CODE_DIR, INPUT_FILE_NAME);
    }

    @Override
    protected long getRunTimeoutMillis(Long timeLimit) {
        // Java 程序有 JVM 启动开销，超时窗口放大 10 倍（与原生模板一致）
        return timeLimit != null ? timeLimit * 10 : TIME_OUT;
    }
}
