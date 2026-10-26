package com.oj.codesandbox.model;

import lombok.Data;

/**
 * 进程执行信息
 */
@Data
public class ExecuteMessage {

    private Integer exitValue;

    private String message;

    private String errorMessage;

    private Long time;

    private Long memory;

    /**
     * 是否超时（被超时线程中断）
     */
    private Boolean timeout;

    /**
     * 是否内存超限（被内存采样线程中断）
     */
    private Boolean memoryLimitExceeded;
}
