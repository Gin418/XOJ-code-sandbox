package com.yupi.yuojcodesandbox.controller;

import com.yupi.yuojcodesandbox.CNativeCodeSandbox;
import com.yupi.yuojcodesandbox.CppDockerCodeSandbox;
import com.yupi.yuojcodesandbox.JavaDockerCodeSandbox;
import com.yupi.yuojcodesandbox.JavaNativeCodeSandbox;
import com.yupi.yuojcodesandbox.PythonNativeCodeSandbox;
import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController("/")
@Slf4j
public class MainController {

    // 定义鉴权请求头和密钥
    private static final String AUTH_REQUEST_HEADER = "auth";

    private static final String AUTH_REQUEST_SECRET = "secretKey";

    @Resource
    private JavaNativeCodeSandbox javaNativeCodeSandbox;

    @Resource
    private CppDockerCodeSandbox cppDockerCodeSandbox;

    @Resource
    private CNativeCodeSandbox cNativeCodeSandbox;

    @Resource
    private PythonNativeCodeSandbox pythonNativeCodeSandbox;

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    /**
     * 执行代码
     *
     * @param executeCodeRequest
     * @return
     */
    @PostMapping("/executeCode")
    ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest, HttpServletRequest request,
                                    HttpServletResponse response) {
        // 基本的认证
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if (!AUTH_REQUEST_SECRET.equals(authHeader)) {
            response.setStatus(403);
            return null;
        }
        if (executeCodeRequest == null) {
            throw new RuntimeException("请求参数为空");
        }
        // 按编程语言分发到对应的代码沙箱
        String language = executeCodeRequest.getLanguage();
        log.info("language: {}", language);
        if ("C++".equalsIgnoreCase(language)) {
            // C++ 采用 Docker 容器内编译和运行，不依赖宿主机 g++ 环境
            return cppDockerCodeSandbox.executeCode(executeCodeRequest);
        }
        if ("C".equalsIgnoreCase(language)) {
            return cNativeCodeSandbox.executeCode(executeCodeRequest);
        }
        if ("Python3".equalsIgnoreCase(language)) {
            return pythonNativeCodeSandbox.executeCode(executeCodeRequest);
        }
        return javaNativeCodeSandbox.executeCode(executeCodeRequest);
    }
}
