package com.oj.codesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import com.oj.codesandbox.model.ExecuteCodeRequest;
import com.oj.codesandbox.model.ExecuteCodeResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * C Docker 代码沙箱：在 gcc 容器内完成编译和运行，具备内存/CPU 限制、禁用网络、
 * 内存采样等隔离能力。容器内编译不依赖宿主机安装 gcc 编译器
 */
@Component
public class CDockerCodeSandbox extends DockerCodeSandboxTemplate {

    /**
     * 镜像：gcc 官方镜像自带 gcc 编译器，与 C++ 沙箱共用同一镜像
     */
    private static final String IMAGE = "gcc:latest";

    private static final String C_FILE_NAME = "main.c";

    public static void main(String[] args) {
        CDockerCodeSandbox cDockerCodeSandbox = new CDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
        String code = ResourceUtil.readStr("testCode/c/Main.c", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("C");
        executeCodeRequest.setMemoryLimit(256L);
        ExecuteCodeResponse executeCodeResponse = cDockerCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    protected String getImage() {
        return IMAGE;
    }

    @Override
    protected String getCodeFileName() {
        return C_FILE_NAME;
    }

    @Override
    protected String getCompileCmdInContainer() {
        // 编译参数与 C 原生模板一致（C11、UTF-8、O2）
        return "gcc -std=c11 -finput-charset=UTF-8 -fexec-charset=UTF-8 -O2 -o " + CONTAINER_CODE_DIR + "/main " + CONTAINER_CODE_DIR + "/main.c";
    }

    @Override
    protected String getRunCmdInContainer(long memoryLimitBytes) {
        // chmod +x 保证 Windows 挂载目录（NTFS 无 Unix 权限位）下的产物在容器内可执行
        return String.format("chmod +x %s/main && %s/main < %s/%s",
                CONTAINER_CODE_DIR, CONTAINER_CODE_DIR, CONTAINER_CODE_DIR, INPUT_FILE_NAME);
    }
}
