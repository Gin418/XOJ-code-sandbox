package com.yupi.yuojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {

    private List<String> inputList;

    /**
     * 题目 ID，非空时沙箱从本地文件 questionData/{questionId}/ 读取输入
     */
    private String questionId;

    private String code;

    private String language;

    private Long timeLimit;

    /**
     * 内存限制（MB）
     */
    private Long memoryLimit;
}
