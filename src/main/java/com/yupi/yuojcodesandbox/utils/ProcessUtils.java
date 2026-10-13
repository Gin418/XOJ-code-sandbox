package com.yupi.yuojcodesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuojcodesandbox.model.ExecuteMessage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.StopWatch;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程工具类
 */
public class ProcessUtils {

    /**
     * 执行进程并获取信息
     *
     * @param runProcess
     * @param opName
     * @return
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String opName) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            // 等待程序执行，获取错误码
            int exitValue = runProcess.waitFor();
            executeMessage.setExitValue(exitValue);
            // Windows 下编译器（javac / g++）的报错信息按系统原生编码（GBK）输出，需用对应编码读取，否则中文乱码
            Charset nativeCharset = Charset.forName(System.getProperty("native.encoding", "GBK"));
            // 正常退出
            if (exitValue == 0) {
                System.out.println(opName + "成功");
                // 分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), nativeCharset));
                List<String> outputStrList = new ArrayList<>();
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(compileOutputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList, "\n"));
            } else {
                // 异常退出
                System.out.println(opName + "失败，错误码： " + exitValue);
                // 分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), nativeCharset));
                List<String> outputStrList = new ArrayList<>();
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(compileOutputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList, "\n"));

                // 分批获取进程的错误输出
                BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream(), nativeCharset));
                // 逐行读取
                List<String> errorOutputStrList = new ArrayList<>();
                // 逐行读取
                String errorCompileOutputLine;
                while ((errorCompileOutputLine = errorBufferedReader.readLine()) != null) {
                    errorOutputStrList.add(errorCompileOutputLine);
                }
                executeMessage.setErrorMessage(StringUtils.join(errorOutputStrList, "\n"));
            }
            stopWatch.stop();
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return executeMessage;
    }

    /**
     * 执行交互式进程并获取信息：把 input 写入子进程的标准输入，读取标准输出和错误输出
     *
     * 注意：输出流必须在 waitFor 之前并发读取，否则子进程输出量超过管道缓冲区时会被阻塞，导致"假超时"
     *
     * @param runProcess
     * @param input      输入内容（可为多行），写入子进程的标准输入
     * @return
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String input) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            // 1. 向子进程标准输入写入输入内容（独立线程，写完即关闭）
            Thread inputThread = new Thread(() -> {
                try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(runProcess.getOutputStream(), StandardCharsets.UTF_8)) {
                    outputStreamWriter.write(input + "\n");
                    outputStreamWriter.flush();
                } catch (IOException e) {
                    // 子进程可能已提前退出，写入失败可忽略
                }
            });
            inputThread.start();
            // 2. 并发读取正常输出（必须在 waitFor 前开始读，防止管道写满导致子进程阻塞）
            StringBuilder outputStringBuilder = new StringBuilder();
            Thread outputThread = new Thread(() -> {
                try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String outputLine;
                    while ((outputLine = bufferedReader.readLine()) != null) {
                        outputStringBuilder.append(outputLine).append("\n");
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            outputThread.start();
            // 3. 并发读取错误输出
            StringBuilder errorOutputStringBuilder = new StringBuilder();
            Thread errorOutputThread = new Thread(() -> {
                try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String errorOutputLine;
                    while ((errorOutputLine = bufferedReader.readLine()) != null) {
                        errorOutputStringBuilder.append(errorOutputLine).append("\n");
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            errorOutputThread.start();
            // 4. 等待程序执行，获取错误码
            int exitValue = runProcess.waitFor();
            executeMessage.setExitValue(exitValue);
            // 等待读取线程结束，保证输出读完整
            outputThread.join();
            errorOutputThread.join();
            inputThread.join();
            stopWatch.stop();
            // 去掉末尾多余的换行
            executeMessage.setMessage(StrUtil.removeSuffix(outputStringBuilder.toString(), "\n"));
            executeMessage.setErrorMessage(StrUtil.removeSuffix(errorOutputStringBuilder.toString(), "\n"));
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return executeMessage;
    }
}