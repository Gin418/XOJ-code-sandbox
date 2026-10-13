package com.yupi.yuojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * C++ Docker 代码沙箱：在 gcc 容器内完成编译和运行，与 Java 沙箱一致，具备内存/CPU 限制、
 * 禁用网络、内存采样等隔离能力。容器内编译不依赖宿主机安装 g++ 编译器
 */
@Component
public class CppDockerCodeSandbox extends CppCodeSandboxTemplate {

    private static final long TIME_OUT = 5000L;

    /**
     * 编译超时（ms）：防止恶意模板元编程拖垮编译
     */
    private static final long COMPILE_TIME_OUT = 20000L;

    /**
     * 编译容器内存上限（MB）：编译过程也需要内存，单独限制防止吃爆沙箱机器
     */
    private static final long COMPILE_MEMORY_LIMIT_MB = 512L;

    /**
     * 测试输入文件名：写入挂载目录后，容器内通过 < 重定向到程序 stdin
     */
    private static final String INPUT_FILE_NAME = "input.txt";

    /**
     * 未传内存限制时的默认内存限制（MB），防止恶意程序耗尽沙箱机器内存
     */
    private static final long DEFAULT_MEMORY_LIMIT_MB = 256L;

    /**
     * 镜像：gcc 官方镜像自带 g++ 编译器与 libstdc++ 运行库，编译和运行共用同一镜像
     */
    private static final String IMAGE = "gcc:latest";

    /**
     * 容器内代码目录（宿主机代码目录挂载到此）
     */
    private static final String CONTAINER_CODE_DIR = "/app";

    /**
     * 镜像首次拉取标记：进程内只拉取一次，后续请求直接复用本地镜像
     */
    private static final AtomicBoolean IMAGE_PULLED = new AtomicBoolean(false);

    public static void main(String[] args) {
        CppDockerCodeSandbox cppDockerCodeSandbox = new CppDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
//        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        String code = ResourceUtil.readStr("testCode/cpp/Main.cpp", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/MemoryError.java", StandardCharsets.UTF_8);
//        String code = ResourceUtil.readStr("testCode/unsafeCode/SleepError.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("C++");
        // 正常路径验证：simpleCompute（stdin 输入）拿到输出；
        // 换成 MemoryError 验证 MLE；换成 SleepError + timeLimit 2000 验证 TLE
        executeCodeRequest.setMemoryLimit(256L);
        ExecuteCodeResponse executeCodeResponse = cppDockerCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    /**
     * 2、在容器内编译代码：复用模板的编译流程入口，编译命令改为容器内 exec 执行
     *
     * @param userCodeFile 用户代码文件（编译产物写回同目录，供后续运行容器使用）
     * @return
     */
    @Override
    public ExecuteMessage compileFile(File userCodeFile) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        DockerClient dockerClient = createDockerClient();
        pullImage(dockerClient);
        // 编译容器单独限制内存（COMPILE_MEMORY_LIMIT_MB），与运行限制解耦
        String containerId = createContainer(dockerClient, userCodeParentPath, COMPILE_MEMORY_LIMIT_MB * 1024 * 1024);
        try {
            return compileCodeInContainer(dockerClient, containerId);
        } finally {
            // 编译完成（无论成败）都清理容器
            removeContainer(dockerClient, containerId);
            closeClient(dockerClient);
        }
    }

    /**
     * 3、运行代码：每个用例创建独立容器（内核维护的 maxUsage 单调不减，独立容器保证内存统计只反映当前用例），
     * 容器内直接运行编译阶段产出的可执行文件
     *
     * @param userCodeFile 用户代码文件（编译产物在同目录）
     * @param inputList    输入用例列表
     * @param timeLimit    时间限制（ms），可为 null
     * @param memoryLimit  内存限制（MB），可为 null
     * @return
     */
    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList, Long timeLimit, Long memoryLimit) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        DockerClient dockerClient = createDockerClient();
        pullImage(dockerClient);

        // 内存限制：MB 转字节；未传则用默认值兜底，防止恶意程序耗尽沙箱机器内存
        long memoryLimitBytes = (memoryLimit != null ? memoryLimit : DEFAULT_MEMORY_LIMIT_MB) * 1024 * 1024;

        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            // 把测试输入写入挂载目录下的文件，容器内通过 < 重定向到程序 stdin（与原生模板一致，支持多行）。
            // 不用 exec 的 stdin 写入，避免 Windows 下程序退出后管道关闭引发的 IO 异常
            FileUtil.writeString(inputArgs + "\n", userCodeParentPath + File.separator + INPUT_FILE_NAME, StandardCharsets.UTF_8);
            String containerId = createContainer(dockerClient, userCodeParentPath, memoryLimitBytes);
            ExecuteMessage executeMessage = executeCodeInContainer(dockerClient, containerId, timeLimit, memoryLimitBytes);
            executeMessageList.add(executeMessage);
            // 清理容器
            removeContainer(dockerClient, containerId);
            // 内存超限时容器已被杀死，无需执行剩余用例
            if (Boolean.TRUE.equals(executeMessage.getMemoryLimitExceeded())) {
                break;
            }
        }
        closeClient(dockerClient);
        return executeMessageList;
    }

    /**
     * 创建 Docker 客户端：默认的 Jersey 传输层不支持 Docker Desktop 的 npipe 协议，
     * 需使用 httpclient5 传输层（项目已引入 docker-java-transport-httpclient5 依赖）
     */
    private DockerClient createDockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 拉取 gcc 镜像：进程内只拉取一次，已存在的镜像 docker 客户端会直接复用
     */
    private void pullImage(DockerClient dockerClient) {
        if (!IMAGE_PULLED.compareAndSet(false, true)) {
            return;
        }
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(IMAGE);
        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
            @Override
            public void onNext(PullResponseItem item) {
                System.out.println("下载镜像：" + item.getStatus());
                super.onNext(item);
            }
        };
        try {
            pullImageCmd.exec(pullImageResultCallback)
                        .awaitCompletion();
        } catch (InterruptedException e) {
            System.out.println("拉取镜像异常");
            throw new RuntimeException(e);
        }
        System.out.println("下载完成");
    }

    /**
     * 创建并启动容器：限制内存和 CPU、禁用网络，以只读根文件系统挂载代码目录
     *
     * @return 容器 ID
     */
    private String createContainer(DockerClient dockerClient, String userCodeParentPath, long memoryLimitBytes) {
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(IMAGE);
        HostConfig hostConfig = new HostConfig();
        // 内存限制（字节）；memorySwap 与内存相同表示禁用 swap，内存超限即被内核 OOM 杀死
        hostConfig.withMemory(memoryLimitBytes);
        hostConfig.withMemorySwap(memoryLimitBytes);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume(CONTAINER_CODE_DIR)));
        // 只读根文件系统下 /tmp 也不可写：挂载 tmpfs 供 g++ 等程序写临时文件（计入容器内存配额）
        hostConfig.withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=64m"));
        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                // 保持容器存活的最小命令，自身几乎不占内存，避免污染内存统计
                .withCmd("tail", "-f", "/dev/null")
                .exec();
        System.out.println(createContainerResponse);
        String containerId = createContainerResponse.getId();
        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();
        return containerId;
    }

    /**
     * 删除容器并关闭客户端（失败仅打印，不影响主流程）
     */
    private void removeContainer(DockerClient dockerClient, String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            System.out.println("删除容器失败：" + e.getMessage());
        }
    }

    private void closeClient(DockerClient dockerClient) {
        try {
            dockerClient.close();
        } catch (IOException e) {
            // 关闭客户端失败可忽略
        }
    }

    /**
     * 在容器内编译代码：执行 g++ 编译命令，收集输出并返回退出码（供模板判断编译错误）
     *
     * @return 编译结果（exitValue 非 0 表示编译失败，errorMessage 携带 g++ 报错信息）
     */
    private ExecuteMessage compileCodeInContainer(DockerClient dockerClient, String containerId) {
        // 编译参数与 C++ 原生模板一致（C++17、UTF-8、O2）；容器内 Linux g++ 输出即为 UTF-8，无需编码转换
        String[] cmdArray = new String[]{"sh", "-c",
                "g++ -std=c++17 -finput-charset=UTF-8 -fexec-charset=UTF-8 -O2 -o " + CONTAINER_CODE_DIR + "/main " + CONTAINER_CODE_DIR + "/main.cpp"};
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStderr(true)
                .withAttachStdout(true)
                // 工作目录设为挂载目录（可写）：根文件系统只读，g++ 需在 cwd 创建临时文件
                .withWorkingDir(CONTAINER_CODE_DIR)
                .exec();
        System.out.println("创建编译命令：" + execCreateCmdResponse);
        String execId = execCreateCmdResponse.getId();

        ExecuteMessage executeMessage = new ExecuteMessage();
        StringBuilder messageBuilder = new StringBuilder();
        StringBuilder errorMessageBuilder = new StringBuilder();
        // 是否编译超时：awaitCompletion 超时返回 false
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (StreamType.STDERR.equals(streamType)) {
                    errorMessageBuilder.append(payload);
                } else {
                    messageBuilder.append(payload);
                }
                super.onNext(frame);
            }
        };

        StopWatch stopWatch = new StopWatch();
        boolean completed;
        try {
            stopWatch.start();
            completed = dockerClient.execStartCmd(execId)
                    .exec(execStartResultCallback)
                    .awaitCompletion(COMPILE_TIME_OUT, TimeUnit.MILLISECONDS);
            stopWatch.stop();
            if (!completed) {
                // 编译超时：杀死容器中断编译
                timedOut.set(true);
                dockerClient.killContainerCmd(containerId).exec();
            }
        } catch (InterruptedException e) {
            System.out.println("编译异常");
            throw new RuntimeException(e);
        }

        if (timedOut.get()) {
            executeMessage.setExitValue(1);
            executeMessage.setErrorMessage("编译超时");
            return executeMessage;
        }
        // 获取退出码：137 = SIGKILL，编译内存超限被内核 OOM 杀死
        Integer exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCode();
        if (exitCode != null && exitCode == 137) {
            executeMessage.setExitValue(exitCode);
            executeMessage.setErrorMessage("编译内存超限");
            return executeMessage;
        }
        executeMessage.setExitValue(exitCode);
        executeMessage.setMessage(messageBuilder.toString());
        executeMessage.setErrorMessage(errorMessageBuilder.toString());
        executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        return executeMessage;
    }

    /**
     * 在容器中执行一次代码：通过 docker stats 周期采样内存占用，超过限制则杀死容器并标记内存超限
     *
     * @return 本次执行的输出、错误、耗时（ms）、峰值内存（KB）及内存超限标记
     */
    private ExecuteMessage executeCodeInContainer(DockerClient dockerClient, String containerId,
                                                  Long timeLimit, long memoryLimitBytes) {
        StopWatch stopWatch = new StopWatch();
        // 输入不拼接为命令行参数：与原生模板和 OJ 判题方式一致，测试数据通过 stdin 传给程序；
        // 借助 sh 重定向 /app/input.txt（挂载目录），支持多行输入；
        // chmod +x 保证 Windows 挂载目录（NTFS 无 Unix 权限位）下的产物在容器内可执行
        String[] cmdArray = new String[]{"sh", "-c",
                String.format("chmod +x %s/main && %s/main < %s/%s",
                        CONTAINER_CODE_DIR, CONTAINER_CODE_DIR, CONTAINER_CODE_DIR, INPUT_FILE_NAME)};
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStderr(true)
                .withAttachStdout(true)
                // 工作目录设为挂载目录：程序相对路径读写落在 /app，随容器清理
                .withWorkingDir(CONTAINER_CODE_DIR)
                .exec();
        System.out.println("创建执行命令：" + execCreateCmdResponse);
        String execId = execCreateCmdResponse.getId();

        ExecuteMessage executeMessage = new ExecuteMessage();
        // 输出可能分多个 frame 到达，需拼接而非覆盖
        StringBuilder messageBuilder = new StringBuilder();
        StringBuilder errorMessageBuilder = new StringBuilder();
        // 是否超时：awaitCompletion 超时返回 false
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (StreamType.STDERR.equals(streamType)) {
                    errorMessageBuilder.append(payload);
                    System.out.println("输出错误结果：" + payload);
                } else {
                    messageBuilder.append(payload);
                    System.out.println("输出结果：" + payload);
                }
                super.onNext(frame);
            }
        };

        // 内存采样：docker stats 周期推送容器内存占用（字节）
        AtomicLong maxMemoryBytes = new AtomicLong(0);
        // 内存超限标记：占用超过限制后置为 true 并杀死容器中断执行
        AtomicBoolean memoryLimitExceeded = new AtomicBoolean(false);
        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
        statsCmd.exec(new ResultCallback<Statistics>() {
            @Override
            public void onNext(Statistics statistics) {
                Long usage = statistics.getMemoryStats().getUsage();
                if (usage == null) {
                    return;
                }
                System.out.println("内存占用：" + usage);
                maxMemoryBytes.accumulateAndGet(usage, Math::max);
                // 占用超过内存限制：杀死容器中断执行
                if (usage > memoryLimitBytes && memoryLimitExceeded.compareAndSet(false, true)) {
                    System.out.println("内存超限，中断");
                    try {
                        dockerClient.killContainerCmd(containerId).exec();
                    } catch (Exception e) {
                        // 容器可能已自行退出，忽略
                    }
                }
            }

            @Override
            public void close() throws IOException {
            }

            @Override
            public void onStart(Closeable closeable) {
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        boolean completed;
        try {
            stopWatch.start();
            // 等待执行完成；超时返回 false（进程仍在容器内运行，需手动杀死容器）
            completed = dockerClient.execStartCmd(execId)
                    .exec(execStartResultCallback)
                    .awaitCompletion(timeLimit != null ? timeLimit : TIME_OUT, TimeUnit.MILLISECONDS);
            stopWatch.stop();
            if (!completed) {
                timedOut.set(true);
                dockerClient.killContainerCmd(containerId).exec();
            }
            // 容器仍存活时再取一次内存快照：maxUsage 由内核维护且单调不减，能拿到真实峰值
            if (!memoryLimitExceeded.get() && !timedOut.get()) {
                dockerClient.statsCmd(containerId).withNoStream(true)
                        .exec(new ResultCallbackTemplate<ResultCallback<Statistics>, Statistics>() {
                            @Override
                            public void onNext(Statistics statistics) {
                                Long maxUsage = statistics.getMemoryStats().getMaxUsage();
                                if (maxUsage != null) {
                                    maxMemoryBytes.accumulateAndGet(maxUsage, Math::max);
                                }
                            }
                        }).awaitCompletion();
            }
            statsCmd.close();
        } catch (InterruptedException e) {
            System.out.println("程序执行异常");
            throw new RuntimeException(e);
        }

        // 获取退出码：137 = SIGKILL，通常是被内核 OOM 杀死（容器内存耗尽）
        InspectExecResponse inspectExecResponse = dockerClient.inspectExecCmd(execId).exec();
        Integer exitCode = inspectExecResponse.getExitCode();
        if (!timedOut.get() && exitCode != null && exitCode == 137) {
            memoryLimitExceeded.set(true);
        }

        executeMessage.setExitValue(exitCode);
        executeMessage.setMessage(messageBuilder.toString());
        executeMessage.setErrorMessage(errorMessageBuilder.toString());
        executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        // 内存单位统一为 KB，与 JudgeInfo 保持一致
        executeMessage.setMemory(maxMemoryBytes.get() / 1024);
        executeMessage.setTimeout(timedOut.get());
        executeMessage.setMemoryLimitExceeded(memoryLimitExceeded.get());
        return executeMessage;
    }
}
