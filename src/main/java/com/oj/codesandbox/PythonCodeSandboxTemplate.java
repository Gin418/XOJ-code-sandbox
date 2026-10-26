package com.oj.codesandbox;

import com.oj.codesandbox.model.ExecuteMessage;
import com.oj.codesandbox.utils.MemoryMonitor;
import com.oj.codesandbox.utils.ProcessUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Python3 代码沙箱模板：定义 Python 的代码文件名、编译命令和运行命令，其余流程复用公共模板。
 * 依赖本机安装的 Python3 解释器（通过 Windows 官方 py 启动器指定 3.x 版本，避免 python 命令指向失效的商店别名）
 */
public abstract class PythonCodeSandboxTemplate extends CodeSandboxTemplate {

    private static final String GLOBAL_PY_FILE_NAME = "main.py";

    @Override
    protected String getCodeFileName() {
        return GLOBAL_PY_FILE_NAME;
    }

    @Override
    protected String getCompileCmd(File userCodeFile) {
        String parentPath = userCodeFile.getParentFile().getAbsolutePath();
        /**
         * Python 无编译产物，用 py_compile 做语法检查充当编译步骤：
         * 语法错误时退出码非 0 且 stderr 有报错信息，由公共模板判定为编译错误（COMPILE_ERROR），与 OJ 判题语义一致
         */
        return String.format("py -3 -m py_compile %s", parentPath + File.separator + GLOBAL_PY_FILE_NAME);
    }

    @Override
    protected String getRunCmd(File userCodeFile, Long memoryLimit) {
        String parentPath = userCodeFile.getParentFile().getAbsolutePath();
        /**
         * -X utf8: 启用 Python 的 UTF-8 模式，使 stdin/stdout 以 UTF-8 编解码。
         * 沙箱以 UTF-8 写入标准输入、读取标准输出，而 Windows 下 Python 默认按 GBK 编解码，中文会乱码或抛 UnicodeDecodeError
         */
        return String.format("py -3 -X utf8 %s", parentPath + File.separator + GLOBAL_PY_FILE_NAME);
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
