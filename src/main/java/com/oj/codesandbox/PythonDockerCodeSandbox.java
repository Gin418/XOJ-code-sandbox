package com.oj.codesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import com.oj.codesandbox.model.ExecuteCodeRequest;
import com.oj.codesandbox.model.ExecuteCodeResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Python3 Docker 代码沙箱：在 python 容器内完成语法检查和运行，具备内存/CPU 限制、
 * 禁用网络、内存采样等隔离能力。容器内解释执行不依赖宿主机 Python 环境。
 * 容器内 LANG=C.UTF-8，Python 默认按 UTF-8 编解码 stdin/stdout，不存在 Windows 下 GBK 乱码问题
 */
@Component
public class PythonDockerCodeSandbox extends DockerCodeSandboxTemplate {

    /**
     * 镜像：官方 python slim 镜像，版本与宿主机 py -3（3.9）一致，保证判题环境统一
     */
    private static final String IMAGE = "python:3.9-slim";

    private static final String PY_FILE_NAME = "main.py";

    public static void main(String[] args) {
        PythonDockerCodeSandbox pythonDockerCodeSandbox = new PythonDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 4", "1 5"));
        String code = ResourceUtil.readStr("testCode/python/Main.py", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("Python3");
        executeCodeRequest.setMemoryLimit(256L);
        ExecuteCodeResponse executeCodeResponse = pythonDockerCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    protected String getImage() {
        return IMAGE;
    }

    @Override
    protected String getCodeFileName() {
        return PY_FILE_NAME;
    }

    @Override
    protected String getCompileCmdInContainer() {
        // Python 无编译产物，用 py_compile 做语法检查充当编译步骤（与原生模板一致）：
        // 语法错误时退出码非 0 且 stderr 有报错信息，由公共模板判定为编译错误（COMPILE_ERROR）
        return "python3 -m py_compile " + CONTAINER_CODE_DIR + "/main.py";
    }

    @Override
    protected String getRunCmdInContainer(long memoryLimitBytes) {
        // 输入通过 stdin 重定向（挂载目录的 input.txt），与原生模板和 OJ 判题方式一致
        return String.format("python3 %s/main.py < %s/%s",
                CONTAINER_CODE_DIR, CONTAINER_CODE_DIR, INPUT_FILE_NAME);
    }
}
