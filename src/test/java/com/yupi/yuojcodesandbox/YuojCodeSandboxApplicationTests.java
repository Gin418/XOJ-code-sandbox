package com.yupi.yuojcodesandbox;

import cn.hutool.core.io.resource.ResourceUtil;
import com.yupi.yuojcodesandbox.model.ExecuteCodeRequest;
import com.yupi.yuojcodesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

@SpringBootTest
class YuojCodeSandboxApplicationTests {

    /**
     * 本地题目测试数据 ID，对应 questionData/{questionId}/ 目录
     */
    private static final String QUESTION_ID = "1794059996187627522";

    @Test
    void contextLoads() {
    }

    /**
     * 验证：用户只提供代码和题目 ID，输入和标准答案从 questionData/{questionId}/ 文件读取并判题
     */
    @Test
    void testJudgeFromQuestionDataFile() {
        JavaNativeCodeSandbox sandbox = new JavaNativeCodeSandbox();
        ExecuteCodeRequest request = new ExecuteCodeRequest();
        request.setCode(ResourceUtil.readStr("testCode/simpleCompute/Main.java", StandardCharsets.UTF_8));
        request.setLanguage("java");
        request.setQuestionId(QUESTION_ID);
        ExecuteCodeResponse response = sandbox.executeCode(request);
        System.out.println(response);
        Assertions.assertEquals("Accepted", response.getJudgeInfo().getMessage());
    }

    /**
     * 验证：C++ 代码执行与判题（依赖本机 g++ 编译器）
     */
    @Test
    void testCppJudgeFromQuestionDataFile() {
        CppNativeCodeSandbox sandbox = new CppNativeCodeSandbox();
        ExecuteCodeRequest request = new ExecuteCodeRequest();
        request.setCode(ResourceUtil.readStr("testCode/cpp/Main.cpp", StandardCharsets.UTF_8));
        request.setLanguage("cpp");
        request.setQuestionId(QUESTION_ID);
        ExecuteCodeResponse response = sandbox.executeCode(request);
        System.out.println(response);
        Assertions.assertEquals("Accepted", response.getJudgeInfo().getMessage());
    }
}
