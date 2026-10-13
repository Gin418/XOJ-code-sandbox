package com.yupi.yuojcodesandbox;

import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import com.yupi.yuojcodesandbox.utils.MemoryMonitor;
import com.yupi.yuojcodesandbox.utils.ProcessUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 代码沙箱模板：定义 Java 语言的代码文件名、编译命令和运行命令，其余流程复用公共模板
 */
public abstract class JavaCodeSandboxTemplate extends CodeSandboxTemplate {

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    @Override
    protected String getCodeFileName() {
        return GLOBAL_JAVA_CLASS_NAME;
    }

    @Override
    protected String getCompileCmd(File userCodeFile) {
        return String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
    }

    @Override
    protected String getRunCmd(File userCodeFile, Long memoryLimit) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        /**
         * java: 启动 Java 虚拟机的命令。
         * -Xmx: 设置最大堆内存。传了内存限制就按限制设置（单位 MB，如 256m = 256 MB），
         *       未传则默认 256 MB 作为安全兜底，防止恶意程序吃爆沙箱机器。
         * -Dfile.encoding=UTF-8: 设置文件编码为 UTF-8。
         * -cp %s: 设置类路径。
         * Main: 指定包含 main 方法的主类。
         */
        String xmx = memoryLimit != null ? String.format("-Xmx%dm", memoryLimit) : "-Xmx256m";
        return String.format("java %s -Dfile.encoding=UTF-8 -cp %s Main", xmx, userCodeParentPath);
    }

    @Override
    protected List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList, Long timeLimit, Long memoryLimit) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String input : inputList) {
            // 输入通过标准输入传给程序（与 OJ 判题方式一致，支持多行输入）
            String runCmd = getRunCmd(userCodeFile, memoryLimit);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                // 超时标记：超时线程中断进程后置为 true，用于判定运行超时（TLE）
                AtomicBoolean timeout = new AtomicBoolean(false);
                // 超时控制
                Thread timeoutThread = new Thread(() -> {
                    try {
                        Thread.sleep((timeLimit == null ? 5000L : timeLimit) * 10);
                        System.out.println("超时了，中断");
                        timeout.set(true);
                        runProcess.destroy();
                    } catch (InterruptedException e) {
                        // 程序已正常结束，超时计时被取消
                    }
                });
                timeoutThread.setDaemon(true);
                timeoutThread.start();
                // 内存采样线程：程序运行期间周期查询峰值工作集（由内核维护，单调不减）
                AtomicLong maxMemory = new AtomicLong(0);
                // 内存超限标记：采样值超过限制后置为 true 并中断进程
                AtomicBoolean memoryLimitExceeded = new AtomicBoolean(false);
                Thread memoryThread = new Thread(() -> {
                    while (runProcess.isAlive()) {
                        Long memory = MemoryMonitor.getProcessMemory(runProcess.pid());
                        if (memory != null) {
                            maxMemory.accumulateAndGet(memory, Math::max);
                            // 内存超限（memoryLimit 单位 MB，采样值单位 KB，转 KB 比较）：中断进程
                            if (memoryLimit != null && memory > memoryLimit * 1024
                                    && memoryLimitExceeded.compareAndSet(false, true)) {
                                System.out.println("内存超限，中断");
                                runProcess.destroy();
                                break;
                            }
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
                memoryThread.setDaemon(true);
                memoryThread.start();
                ExecuteMessage executeMessage = ProcessUtils.runInteractProcessAndGetMessage(runProcess, input);
                // 程序已结束，取消超时线程
                timeoutThread.interrupt();
                // 停止内存采样，记录峰值（KB）
                memoryThread.interrupt();
                memoryThread.join();
                long memory = maxMemory.get();
                if (memory > 0) {
                    executeMessage.setMemory(memory);
                }
                // 标记是否内存超限
                executeMessage.setMemoryLimitExceeded(memoryLimitExceeded.get());
                // 标记是否超时
                executeMessage.setTimeout(timeout.get());
                System.out.println(executeMessage);
                executeMessageList.add(executeMessage);
            } catch (Exception e) {
                throw new RuntimeException("执行错误", e);
            }
        }
        return executeMessageList;
    }
}
