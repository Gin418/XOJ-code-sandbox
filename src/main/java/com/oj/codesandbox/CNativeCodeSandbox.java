package com.oj.codesandbox;

import com.oj.codesandbox.model.ExecuteCodeRequest;
import com.oj.codesandbox.model.ExecuteCodeResponse;
import org.springframework.stereotype.Component;

/**
 * C 原生代码沙箱实现（直接复用模板方法）
 */
@Component
public class CNativeCodeSandbox extends CCodeSandboxTemplate {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        return super.executeCode(executeCodeRequest);
    }
}
