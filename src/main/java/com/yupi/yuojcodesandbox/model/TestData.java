package com.yupi.yuojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 题目测试数据：一组输入，从本地文件读取（标准答案由主站管理，沙箱不判题）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestData {

    /**
     * 该组测试点的输入（可为多行）
     */
    private String input;
}
