package com.oj.codesandbox;

import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.oj.codesandbox.model.ExecuteMessage;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Docker 代码沙箱公共模板：在语言对应镜像的容器内完成编译和运行，具备内存/CPU 限制、
 * 禁用网络、内存采样等隔离能力。容器内编译不依赖宿主机安装编译器。
 * 子类只需提供镜像名、代码文件名、容器内编译命令和运行命令
 */
public abstract class DockerCodeSandboxTemplate extends CodeSandboxTemplate {

    /**
     * 运行超时默认值（ms）：未传时间限制时兜底
     */
    protected static final long TIME_OUT = 5000L;

    /**
     * 编译超时（ms）：防止恶意模板元编程拖垮编译
     */
    protected static final long COMPILE_TIME_OUT = 20000L;

    /**
     * 编译容器内存上限（MB）：编译过程也需要内存，单独限制防止吃爆沙箱机器
     */
    protected static final long COMPILE_MEMORY_LIMIT_MB = 512L;

    /**
     * 测试输入文件名：写入挂载目录后，容器内通过 < 重定向到程序 stdin
     */
    protected static final String INPUT_FILE_NAME = "input.txt";

    /**
     * 未传内存限制时的默认内存限制（MB），防止恶意程序耗尽沙箱机器内存
     */
    protected static final long DEFAULT_MEMORY_LIMIT_MB = 256L;

    /**
     * 容器内代码目录（宿主机代码目录挂载到此）
     */
    protected static final String CONTAINER_CODE_DIR = "/app";

    /**
     * 镜像拉取超时（分钟）：gcc 镜像约 1.5GB，正常拉取预留充足时间
     */
    protected static final long PULL_IMAGE_TIME_OUT = 10L;

    /**
     * 镜像拉取标记：按镜像名记录，进程内每镜像只拉取一次，后续请求直接复用本地镜像
     */
    private static final Map<String, AtomicBoolean> IMAGE_PULLED_MAP = new ConcurrentHashMap<>();

    /**
     * 沙箱使用的运行镜像，如 gcc:latest / python:3.9-slim
     */
    protected abstract String getImage();

    /**
     * 编译镜像：默认与运行镜像相同；编译运行镜像分离的语言（如 Java 的 JDK/JRE）可覆写
     */
    protected String getCompileImage() {
        return getImage();
    }

    /**
     * 容器内编译命令（sh -c 方式执行），如 g++ / gcc / python3 -m py_compile
     */
    protected abstract String getCompileCmdInContainer();

    /**
     * 容器内运行命令（sh -c 方式执行，含 stdin 重定向），如 ./main < /app/input.txt
     *
     * @param memoryLimitBytes 内存限制（字节），Java 用它动态设置 -Xmx 堆上限
     */
    protected abstract String getRunCmdInContainer(long memoryLimitBytes);

    /**
     * 运行超时窗口（ms）：默认直接用时间限制；Java 等启动开销大的语言可放大窗口
     */
    protected long getRunTimeoutMillis(Long timeLimit) {
        return timeLimit != null ? timeLimit : TIME_OUT;
    }

    @Override
    protected final String getCompileCmd(File userCodeFile) {
        throw new UnsupportedOperationException("Docker 沙箱在容器内编译，不走宿主机编译命令");
    }

    @Override
    protected final String getRunCmd(File userCodeFile, Long memoryLimit) {
        throw new UnsupportedOperationException("Docker 沙箱在容器内运行，不走宿主机运行命令");
    }

    /**
     * 2、在容器内编译代码：编译命令改为容器内 exec 执行
     *
     * @param userCodeFile 用户代码文件（编译产物写回同目录，供后续运行容器使用）
     * @return
     */
    @Override
    public ExecuteMessage compileFile(File userCodeFile) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        DockerClient dockerClient = createDockerClient();
        pullImage(dockerClient, getCompileImage());
        // 编译容器单独限制内存（COMPILE_MEMORY_LIMIT_MB），与运行限制解耦
        String containerId = createContainer(dockerClient, getCompileImage(), userCodeParentPath, COMPILE_MEMORY_LIMIT_MB * 1024 * 1024);
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
     * 容器内直接运行编译阶段产出的可执行文件（或脚本）
     *
     * @param userCodeFile 用户代码文件（编译产物在同目录）
     * @param inputList    输入用例列表
     * @param timeLimit    时间限制（ms），可为 null
     * @param memoryLimit  内存限制（MB），可为 null
     * @return
     */
    @Override
    protected List<ExecuteMessage>  runFile(File userCodeFile, List<String> inputList, Long timeLimit, Long memoryLimit) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        DockerClient dockerClient = createDockerClient();
        pullImage(dockerClient, getImage());

        // 内存限制：MB 转字节；未传则用默认值兜底，防止恶意程序耗尽沙箱机器内存
        long memoryLimitBytes = (memoryLimit != null ? memoryLimit : DEFAULT_MEMORY_LIMIT_MB) * 1024 * 1024;

        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            // 把测试输入写入挂载目录下的文件，容器内通过 < 重定向到程序 stdin（与原生模板一致，支持多行）。
            // 不用 exec 的 stdin 写入，避免 Windows 下程序退出后管道关闭引发的 IO 异常
            FileUtil.writeString(inputArgs + "\n", userCodeParentPath + File.separator + INPUT_FILE_NAME, StandardCharsets.UTF_8);
            String containerId = createContainer(dockerClient, getImage(), userCodeParentPath, memoryLimitBytes);
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
     * 拉取镜像：同一镜像进程内只拉取一次，已存在的镜像 docker 客户端会直接复用
     */
    private void pullImage(DockerClient dockerClient, String image) {
        AtomicBoolean pulled = IMAGE_PULLED_MAP.computeIfAbsent(image, k -> new AtomicBoolean(false));
        if (!pulled.compareAndSet(false, true)) {
            return;
        }
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
            @Override
            public void onNext(PullResponseItem item) {
                System.out.println("下载镜像：" + item.getStatus());
                super.onNext(item);
            }
        };
        boolean completed = false;
        try {
            // 带超时等待，避免 Docker 引擎未启动或网络异常时无限挂起
            completed = pullImageCmd.exec(pullImageResultCallback)
                    .awaitCompletion(PULL_IMAGE_TIME_OUT, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            System.out.println("拉取镜像异常");
            throw new RuntimeException(e);
        } finally {
            // 拉取失败或超时：重置标记，允许后续请求重试
            if (!completed) {
                pulled.set(false);
            }
        }
        System.out.println("下载完成");
    }

    /**
     * 创建并启动容器：限制内存和 CPU、禁用网络，以只读根文件系统挂载代码目录
     *
     * @return 容器 ID
     */
    private String createContainer(DockerClient dockerClient, String image, String userCodeParentPath, long memoryLimitBytes) {
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        // 内存限制（字节）；memorySwap 与内存相同表示禁用 swap，内存超限即被内核 OOM 杀死
        hostConfig.withMemory(memoryLimitBytes);
        hostConfig.withMemorySwap(memoryLimitBytes);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume(CONTAINER_CODE_DIR)));
        // 只读根文件系统下 /tmp 也不可写：挂载 tmpfs 供编译器/解释器写临时文件（计入容器内存配额）
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
     * 在容器内编译代码：执行子类提供的编译命令，收集输出并返回退出码（供模板判断编译错误）
     *
     * @return 编译结果（exitValue 非 0 表示编译失败，errorMessage 携带编译器报错信息）
     */
    private ExecuteMessage compileCodeInContainer(DockerClient dockerClient, String containerId) {
        // 容器内 Linux 编译器输出即为 UTF-8，无需编码转换
        String[] cmdArray = new String[]{"sh", "-c", getCompileCmdInContainer()};
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStderr(true)
                .withAttachStdout(true)
                // 工作目录设为挂载目录（可写）：根文件系统只读，编译器/解释器需在 cwd 创建临时文件
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
        // 借助 sh 重定向 /app/input.txt（挂载目录），支持多行输入
        String[] cmdArray = new String[]{"sh", "-c", getRunCmdInContainer(memoryLimitBytes)};
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
                    .awaitCompletion(getRunTimeoutMillis(timeLimit), TimeUnit.MILLISECONDS);
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
