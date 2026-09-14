package com.flowdesk.domain.knowledge;

/**
 * Embedding 描述符：说明「这一批向量由谁、用哪个模型、多少维」生成。
 *
 * <p>它同时是<b>领域不变量</b>的载体和<b>可持久化的元数据</b>：向量离开生成它的模型之后
 * 就失去意义，因此必须与 provider / model / dimensions 一起存储。后续检索阶段可以据此判断
 * 「库里的向量是不是当前模型生成的」，而不需要再去问模型。</p>
 *
 * <h2>为什么要固定 1024 维</h2>
 * <p>本项目的存储列是 {@code vector(1024)}，领域与数据库各自都强制一次：
 * 维度不匹配的向量一旦写进库，后续检索会在「同一条 SQL 里比较不同长度的向量」时失败，
 * 排查成本远高于在构造期拒绝。</p>
 *
 * <p>provider / model 只接受受限字符集：它们会成为数据库列值并可能出现在响应里，
 * 因此不能包含空白、控制字符或路径分隔符。</p>
 *
 * @param provider   向量服务提供方，例如 {@code dashscope}
 * @param model      模型标识，例如 {@code text-embedding-v4}
 * @param dimensions 向量维度，必须等于 {@link #REQUIRED_DIMENSIONS}
 */
public record EmbeddingDescriptor(String provider, String model, int dimensions) {

    /** 本项目固定使用的向量维度。 */
    public static final int REQUIRED_DIMENSIONS = 1024;

    /** provider 最大长度。 */
    public static final int MAX_PROVIDER_LENGTH = 32;

    /** model 最大长度。 */
    public static final int MAX_MODEL_LENGTH = 128;

    /**
     * 紧凑构造器：校验 provider、model 与维度。
     *
     * @throws KnowledgeDomainException 任一字段非法
     */
    public EmbeddingDescriptor {
        provider = requireIdentifier(provider, "向量服务提供方", MAX_PROVIDER_LENGTH);
        model = requireIdentifier(model, "向量模型标识", MAX_MODEL_LENGTH);
        if (dimensions != REQUIRED_DIMENSIONS) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                    "向量维度必须等于 " + REQUIRED_DIMENSIONS);
        }
    }

    /**
     * @param provider 向量服务提供方
     * @param model    模型标识
     * @return 1024 维的描述符
     */
    public static EmbeddingDescriptor of(String provider, String model) {
        return new EmbeddingDescriptor(provider, model, REQUIRED_DIMENSIONS);
    }

    /**
     * 受限标识符：非空、已 strip、长度受限、不含空白/控制字符/路径分隔符。
     *
     * @param value     原始值
     * @param fieldName 字段名（用于错误文案，不含原始值）
     * @param maxLength 最大长度
     * @return 校验通过的值
     */
    private static String requireIdentifier(String value, String fieldName, int maxLength) {
        if (value == null) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                    fieldName + "不能为空");
        }
        if (value.isBlank()) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                    fieldName + "不能为空白");
        }
        if (value.length() > maxLength) {
            throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                    fieldName + "长度不能超过 " + maxLength + " 个字符");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)
                    || character == '/' || character == '\\') {
                throw new KnowledgeDomainException(KnowledgeErrorCode.INVALID_EMBEDDING_DESCRIPTOR,
                        fieldName + "不能包含空白、控制字符或路径分隔符");
            }
        }
        return value;
    }
}
