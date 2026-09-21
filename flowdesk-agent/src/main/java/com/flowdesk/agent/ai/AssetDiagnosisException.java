package com.flowdesk.agent.ai;

/**
 * 资产诊断的引用校验失败（FD-0017-A）。
 *
 * <p>{@link #getMessage()} 是固定的稳定文案（就是失败类别的名字），不含模型回答、提示词、
 * 证据内容或任何远端数据；它只用于服务端诊断，最终对外仍是
 * {@link com.flowdesk.application.ai.AiProviderException} 的固定文案。</p>
 */
public class AssetDiagnosisException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient AssetDiagnosisFailure failure;

    /**
     * @param failure 稳定失败类别
     */
    public AssetDiagnosisException(AssetDiagnosisFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    /**
     * @return 稳定失败类别
     */
    public AssetDiagnosisFailure failure() {
        return this.failure;
    }
}
