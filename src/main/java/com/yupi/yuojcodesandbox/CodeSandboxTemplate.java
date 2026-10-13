package com.yupi.yuojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import com.yupi.yuojcodesandbox.model.ExecuteResultEnum;
import com.yupi.yuojcodesandbox.model.JudgeInfo;
import com.yupi.yuojcodesandbox.model.TestData;
import com.yupi.yuojcodesandbox.utils.MemoryMonitor;
import com.yupi.yuojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 代码沙箱模板方法：定义"保存代码 -> 编译 -> 执行 -> 判题 -> 清理"的通用流程，
 * 具体语言（Java、C++ 等）只需实现代码文件名、编译命令和运行命令
 */
@Slf4j
public abstract class CodeSandboxTemplate implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();
        Long timeLimit = executeCodeRequest.getTimeLimit();
        Long memoryLimit = executeCodeRequest.getMemoryLimit();

//        1. 获取测试数据（输入）：传了题目 ID 就从本地文件读取，否则用请求参数
        List<String> inputList = executeCodeRequest.getInputList();

        // 代码文件大小（字节）
        long codeSize = 0;
        try {
//        2. 把用户的代码保存为文件
            File userCodeFile = saveCodeToFile(code);
            codeSize = userCodeFile.length();

//        3. 编译代码，得到 class 文件 / 可执行文件
            ExecuteMessage compileFileExecuteMessage = compileFile(userCodeFile);
            System.out.println(compileFileExecuteMessage);
            // 编译失败：正常返回响应，携带编译错误详情（如 javac / g++ 报错信息）
            if (compileFileExecuteMessage.getExitValue() != 0) {
                ExecuteCodeResponse compileErrorResponse = new ExecuteCodeResponse();
                compileErrorResponse.setOutputList(new ArrayList<>());
                compileErrorResponse.setMessage(StrUtil.isNotBlank(compileFileExecuteMessage.getErrorMessage())
                        ? compileFileExecuteMessage.getErrorMessage() : "编译失败");
                JudgeInfo judgeInfo = new JudgeInfo();
                judgeInfo.setMessage(ExecuteResultEnum.COMPILE_ERROR.getValue());
                compileErrorResponse.setJudgeInfo(judgeInfo);
                compileErrorResponse.setCodeSize(codeSize);
                // 编译失败也要清理代码文件
                deleteFile(userCodeFile);
                log.info("ExecuteCodeResponse: {}", compileErrorResponse);
                return compileErrorResponse;
            }

            // 4. 执行代码，得到输出结果
            List<ExecuteMessage> executeMessageList = runFile(userCodeFile, inputList, timeLimit, memoryLimit);

//        5. 收集整理输出结果（与标准答案的对比判题由主站完成）
            ExecuteCodeResponse outputResponse = getOutputResponse(executeMessageList);
            outputResponse.setCodeSize(codeSize);
            log.info("outputResponse: {}", outputResponse);

//        6. 文件清理
            boolean b = deleteFile(userCodeFile);
            if (!b) {
                log.error("deleteFile error, userCodeFilePath = {}", userCodeFile.getAbsolutePath());
            }
            return outputResponse;
        } catch (Exception e) {
            // 沙箱内部异常（如启动进程失败、读写文件失败）：正常返回系统错误响应，而不是抛异常导致 500
            log.error("executeCode error", e);
            ExecuteCodeResponse errorResponse = getErrorResponse(e);
            errorResponse.setCodeSize(codeSize);
            return errorResponse;
        }
    }

    /**
     * 代码文件名，如 Java 是 Main.java，C++ 是 main.cpp
     */
    protected abstract String getCodeFileName();

    /**
     * 编译命令，如 javac / g++
     */
    protected abstract String getCompileCmd(File userCodeFile);

    /**
     * 运行命令，如 java -cp ... Main / main.exe
     *
     * @param memoryLimit 内存限制（MB），可为 null；Java 用它动态设置 -Xmx 堆上限
     */
    protected abstract String getRunCmd(File userCodeFile, Long memoryLimit);

    /**
     * 1. 把用户的代码保存为文件
     * @param code 用户代码
     * @return
     */
    public File saveCodeToFile(String code) {
        //获取当前用户的工作目录
        String userDir = System.getProperty("user.dir");
        //File.separator 这是平台独立的文件路径分隔符。例如，在 Windows 上是 \，在 Unix 或 Linux 上是 /。
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断全局代码目录是否存在，没有则新建
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        // 把用户的代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + getCodeFileName();
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        return userCodeFile;
    }

    /**
     * 2、编译代码
     * @param userCodeFile
     * @return
     */
    public ExecuteMessage compileFile(File userCodeFile) {
        String compileCmd = getCompileCmd(userCodeFile);
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            // 编译失败时不抛异常，错误信息在 errorMessage 中，由 executeCode 组装响应返回
            return ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 3、执行文件，获得执行结果列表
     * @param userCodeFile
     * @param inputList
     * @return
     */
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
                        Thread.sleep((timeLimit == null ? 5000L : timeLimit));
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

    /**
     * 4、获取输出结果
     * @param executeMessageList
     * @return
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        // 取内存峰值最大值（KB）
        long maxMemory = 0;
        // 执行结果类型（运行时错误 / 超时），null 表示正常执行完成
        String judgeMessage = null;
        for (ExecuteMessage executeMessage : executeMessageList) {
            // 先统计资源（时间、内存），再做结果类型判断，保证错误场景也能拿到统计值
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            Long memory = executeMessage.getMemory();
            if (memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
            // 内存超限（Memory Limit Exceeded）：进程被中断，stderr 通常为空，需单独识别
            if (Boolean.TRUE.equals(executeMessage.getMemoryLimitExceeded())) {
                executeCodeResponse.setMessage(ExecuteResultEnum.MEMORY_LIMIT_EXCEEDED.getValue());
                judgeMessage = ExecuteResultEnum.MEMORY_LIMIT_EXCEEDED.getValue();
                break;
            }
            // 超时（Time Limit Exceeded）：进程被超时中断，输出通常为空，需单独识别
            if (Boolean.TRUE.equals(executeMessage.getTimeout())) {
                executeCodeResponse.setMessage(ExecuteResultEnum.TIME_LIMIT_EXCEEDED.getValue());
                judgeMessage = ExecuteResultEnum.TIME_LIMIT_EXCEEDED.getValue();
                break;
            }
            // 运行时错误：stderr 非空说明程序异常退出（异常堆栈 / OOM）
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // Java 堆内存不足（OutOfMemoryError）：超限即 OOM，视为内存超限
                judgeMessage = errorMessage.contains("OutOfMemoryError")
                        ? ExecuteResultEnum.MEMORY_LIMIT_EXCEEDED.getValue()
                        : ExecuteResultEnum.SIGNALLED.getValue();
                break;
            }
            outputList.add(executeMessage.getMessage());
        }
        // 正常运行完成
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
        judgeInfo.setMessage(judgeMessage);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    /**
     * 5、删除文件
     * @param userCodeFile
     * @return
     */
    public boolean deleteFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
            return del;
        }
        return true;
    }

    /**
     * 6、获取错误响应
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        JudgeInfo judgeInfo = new JudgeInfo();
        // 沙箱内部错误（System Error）
        judgeInfo.setMessage(ExecuteResultEnum.INTERNAL_ERROR.getValue());
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }
}
