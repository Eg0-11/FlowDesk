/*
 * FlowDesk 知识文档区前端脚本（FD-0023-D）
 *
 * 设计约束：
 * 1. 只使用同源相对路径；不引入框架、不引入 CDN。
 * 2. 页面加载阶段不得发起任何请求（包括健康检查）：全部请求由用户点击触发。
 *    本文件不注册任何 DOMContentLoaded 期的网络调用，只注册事件监听。
 * 3. 只操作本文件负责的知识文档区（#knowledge 及其子元素），不触碰工单区。
 * 4. 渲染一律走 textContent / replaceChildren，绝不把服务端字符串当标记插入。
 * 5. 上传与解析都必须由用户点击触发；解析前必须重新 GET 核对文档 ID、状态与用户看到的 version，
 *    再由该版本构造带双引号的 If-Match，绝不写死 "0"。
 * 6. 解析成功响应的 ETag 必须与响应体 version 自洽；不假定解析只加 1。
 * 7. 412 / 409 / 400 / 404 分别提示；网络中断或 5xx 一律提示「结果待确认，请刷新」，不自动重试。
 * 8. 没有列表 API，因此本区如实标注「按 ID 查找」，不伪称能列出全部文档。
 *
 * FD-0023-D-R1 补充约束：
 * 9. 「拿到响应头」不等于「拿到响应体」：读取 body 可能在中途失败（连接被重置、流被截断）。
 *    这类失败必须与「请求根本没发出去」区分开，并且**永远不能**留下未处理的 Promise 拒绝或
 *    永久「进行中」状态 —— 按钮会一直禁用，用户再也点不动。因此：
 *    - requestJson 自己吞掉 body 读取失败，把它表达成 bodyReadFailed 标记；
 *    - 四条调用路径（上传 / 查询 / 解析前重查 / 解析写入）都必须在两条出口上收尾；
 *    - 写请求（上传、解析）的 body 读取失败一律按「结果待确认」处理并锁住写路径，绝不自动重试。
 * 10. 解析返回 422 / 413 时响应体是 problem+json，不是 ParsedDocumentResponse：
 *     必须按错误体渲染（固定 title/detail + failureCode），不得当成解析结果渲染，
 *     否则会凭空显示出空的「切片数」「解析完成时间」，把问题体当成文档快照。
 * 11. 刷新元数据只更新展示：冲突后重新 GET 拿到的版本可能又已被别人改动，
 *     因此刷新路径不得重建可写目标（currentDocument 保持为空），
 *     避免「刷新一次就又拿旧版本去写」。
 */
(function () {
  'use strict';

  var DOCUMENTS_PATH = '/api/v1/knowledge/documents';

  // 与后端 DocumentFormat 的 acceptedExtensions 保持一致（服务端仍是最终校验者）。
  var FORMAT_HINTS = ['.pdf', '.docx', '.md', '.txt', '.markdown', '.text'];

  // 与后端 application.yml 的 multipart.max-file-size / upload.max-size 保持一致，
  // 仅作为提示文本；真正的上限判定永远以服务端响应为准。
  var MAX_UPLOAD_MB = 20;

  // 只有这两个状态允许用户手动触发解析（其余状态的解析请求会被服务端以 409 拒绝）。
  var PARSABLE_STATUSES = ['UPLOADED', 'PARSE_FAILED'];

  var STATUS_LABELS = {
    UPLOADED: 'UPLOADED（已上传，未解析）',
    PARSING: 'PARSING（解析中）',
    PARSED: 'PARSED（已解析）',
    PARSE_FAILED: 'PARSE_FAILED（解析失败）',
    INDEXING: 'INDEXING（索引中）',
    INDEXED: 'INDEXED（已索引）',
    INDEX_FAILED: 'INDEX_FAILED（索引失败）'
  };

  // 解析失败时服务端会给出 failureCode；只做展示，不做任何推断。
  var FAILURE_LABELS = {
    CORRUPTED_DOCUMENT: '文档已损坏，无法解析',
    ENCRYPTED_DOCUMENT: '文档已加密，无法解析',
    UNSUPPORTED_DOCUMENT_CONTENT: '文档内容与声明格式不符或不支持',
    EMPTY_EXTRACTED_TEXT: '未能从文档中提取到文本',
    EXTRACTED_TEXT_TOO_LARGE: '提取出的文本超过服务端上限',
    TOO_MANY_CHUNKS: '切片数量超过服务端上限',
    PARSER_FAILURE: '解析器内部错误'
  };

  // ---- 本区状态 -------------------------------------------------------------
  var uploading = false;      // 上传请求在途
  var parsing = false;        // 解析请求在途
  var copyTimer = null;       // 复制反馈的定时器

  // 用户当前"看到的"文档：只有在 GET 成功且校验通过后才会被赋值。
  // 解析前的重新 GET 就是为了核对它，避免拿着过期版本去写。
  var currentDocument = null; // { id, version, status }

  // 解析区锁定标志：一旦出现版本漂移、状态变化、不确定结果或不完整成功，
  // 就锁住写路径；此时刷新的只读展示不得把按钮重新点亮。
  // 只有用户重新「按 ID 查询」才能解锁（那是唯一重置该标志的入口）。
  var parseLocked = false;

  // 每次 GET 递增；用于丢弃迟到的旧响应，避免覆盖用户刚看到的新结果。
  var lookupToken = 0;

  // ---- 通用小工具 -----------------------------------------------------------

  function element(id) {
    return document.getElementById(id);
  }

  function setText(node, text) {
    if (node) {
      node.textContent = text;
    }
  }

  function setClass(node, className) {
    if (node) {
      node.className = className;
    }
  }

  function show(node, visible) {
    if (node) {
      node.hidden = !visible;
    }
  }

  function setDisabled(node, disabled) {
    if (node) {
      node.disabled = !!disabled;
    }
  }

  function isStrongETag(value) {
    return typeof value === 'string' && /^"[^"]*"$/.test(value);
  }

  function isFiniteNumber(value) {
    return typeof value === 'number' && isFinite(value);
  }

  function missingField(body, field) {
    return body === null || typeof body !== 'object' || body[field] === undefined || body[field] === null;
  }

  function versionTagOf(documentBody) {
    return '"' + String(documentBody.version) + '"';
  }

  function labelOfStatus(status) {
    return STATUS_LABELS[status] || String(status);
  }

  function labelOfFailure(code) {
    return FAILURE_LABELS[code] || String(code);
  }

  /**
   * 把 problem+json 的 title/detail 组合成一句可读的失败说明。
   * 服务端的 detail 已经做过脱敏，这里原样展示即可，不做二次拼接推断。
   */
  function describeProblem(status, body) {
    var parts = [];
    if (body && typeof body === 'object') {
      if (typeof body.title === 'string' && body.title.length > 0) {
        parts.push(body.title);
      }
      if (typeof body.detail === 'string' && body.detail.length > 0 && body.detail !== body.title) {
        parts.push(body.detail);
      }
    }
    var head = 'HTTP ' + status;
    if (parts.length === 0) {
      return head + '：服务端返回了无法识别的错误结构。';
    }
    return head + '：' + parts.join(' — ');
  }

  function problemCode(body) {
    if (body && typeof body === 'object' && typeof body.code === 'string') {
      return body.code;
    }
    return '';
  }

  /**
   * 统一的请求包装：**永不拒绝**，把结果压成一个纯数据结构，便于分支判断。
   *
   * <p>返回 { status, body, eTag, transportError, bodyReadFailed, formatProblem }。</p>
   *
   * <p>三种失败各占一个字段，语义互不重叠：</p>
   * <ul>
   *   <li>{@code transportError} —— 连响应都没拿到（DNS/连接被拒/请求被中断）；</li>
   *   <li>{@code bodyReadFailed} —— <b>拿到了响应头</b>（{@code status} 有效）但读 body 失败。
   *       写请求遇到它意味着「服务端可能已经处理了，但结果读不出来」，属于典型的
   *       「结果待确认」；把它和网络中断分开，是为了不说「请求没发出去」这种错话。</li>
   *   <li>{@code formatProblem} —— body 读到了但格式不对（空、非 JSON、不是对象）。</li>
   * </ul>
   *
   * <p>{@code response.text()} 本身也会拒绝（流被重置、内容长度不符），
   * 这里必须就地 catch：否则整个 Promise 会拒绝，调用方若没挂 {@code .catch}
   * 就会留下未处理的拒绝，并且「进行中」标记永远不会复位。</p>
   */
  function requestJson(url, options) {
    var settings = options || {};
    var init = { method: settings.method || 'GET', headers: settings.headers || {} };
    if (settings.body !== undefined) {
      init.body = settings.body;
    }
    return fetch(url, init).then(function (response) {
      var eTag = response.headers.get('ETag');
      var status = response.status;
      var ok = response.ok;
      // 读取 body 是一段独立的、可能失败的过程，单独兜住。
      var readBody = response.text().then(function (text) {
        return { text: text, failed: false };
      }, function () {
        return { text: '', failed: true };
      });
      return readBody.then(function (outcome) {
        if (outcome.failed) {
          // 拿到了响应头但没读到响应体：既不是「没发出」，也不是「格式不对」。
          return {
            status: status,
            body: null,
            eTag: eTag,
            transportError: false,
            bodyReadFailed: true,
            formatProblem: null
          };
        }
        var text = outcome.text;
        if (text.length === 0) {
          return {
            status: status,
            body: null,
            eTag: eTag,
            transportError: false,
            bodyReadFailed: false,
            formatProblem: ok ? '响应体为空，无法确认结果。' : null
          };
        }
        var parsed;
        try {
          parsed = JSON.parse(text);
        } catch (error) {
          return {
            status: status,
            body: null,
            eTag: eTag,
            transportError: false,
            bodyReadFailed: false,
            formatProblem: ok ? '响应体不是合法 JSON，无法确认结果。' : null
          };
        }
        if (ok && (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed))) {
          return {
            status: status,
            body: null,
            eTag: eTag,
            transportError: false,
            bodyReadFailed: false,
            formatProblem: '响应体不是 JSON 对象，无法确认结果。'
          };
        }
        return {
          status: status,
          body: parsed,
          eTag: eTag,
          transportError: false,
          bodyReadFailed: false,
          formatProblem: null
        };
      });
    }, function () {
      return {
        status: 0,
        body: null,
        eTag: null,
        transportError: true,
        bodyReadFailed: false,
        formatProblem: null
      };
    });
  }

  /**
   * 给请求结果挂一个统一的异常兜底。
   *
   * <p>{@link requestJson} 已经承诺不拒绝，但「不拒绝」是它的实现细节，
   * 调用方不应该依赖它来保证状态复位：任何在 {@code .then} 里抛出的渲染异常
   * 同样会跳过收尾逻辑，让按钮永久禁用。因此四个入口都显式挂上 {@code .catch}，
   * 把「无论发生什么都收尾」变成结构上的保证，而不是一次性的细心。</p>
   *
   * @param promise   requestJson 返回的 Promise
   * @param onFinish  无论成功失败都必须执行的收尾（复位 busy 与按钮）
   * @param onCrash   在收尾之外还要做的提示；写请求用它与「结果待确认」对齐
   */
  function guard(promise, onFinish, onCrash) {
    return promise.then(function (result) {
      onFinish();
      return result;
    }, function () {
      onFinish();
      onCrash();
      return null;
    });
  }

  // ---- 静态提示文本 ---------------------------------------------------------

  function renderStaticHints() {
    setText(element('knowledge-format-hint'), '支持的扩展名：' + FORMAT_HINTS.join(' / ') + '。');
    setText(
      element('knowledge-size-hint'),
      '单文件上限约 ' + MAX_UPLOAD_MB + ' MB（服务端为准，超限返回 413）。'
    );
  }

  // ---- 状态区渲染 -----------------------------------------------------------

  function setKnowledgeState(kind, message) {
    var node = element('knowledge-state');
    if (!node) {
      return;
    }
    node.textContent = message;
    setClass(node, 'state ' + kind);
    show(node, true);
  }

  function clearKnowledgeState() {
    show(element('knowledge-state'), false);
  }

  function setKnowledgeError(message) {
    var node = element('knowledge-error');
    if (!node) {
      return;
    }
    if (message) {
      node.textContent = message;
      show(node, true);
    } else {
      setText(node, '');
      show(node, false);
    }
  }

  // ---- 按 ID 查找 -----------------------------------------------------------

  function clearDocumentView() {
    currentDocument = null;
    show(element('knowledge-doc'), false);
    var body = element('knowledge-doc-body');
    if (body) {
      body.replaceChildren();
    }
    setKnowledgeError('');
  }

  /**
   * 用户显式「按 ID 查询」是**唯一**的解锁入口：只有用户重新发起一次完整的查询，
   * 才允许解析区重新变得可写。
   */
  function unlockParseArea() {
    parseLocked = false;
  }

  function appendRow(box, label, value) {
    var row = document.createElement('div');
    row.className = 'detail-row';
    var name = document.createElement('div');
    name.className = 'field-name';
    name.textContent = label;
    var text = document.createElement('div');
    text.className = 'field-value';
    text.textContent = (value === undefined || value === null || value === '') ? '—' : String(value);
    row.appendChild(name);
    row.appendChild(text);
    box.appendChild(row);
    return text;
  }

  /**
   * 渲染文档元数据。所有文本都通过 textContent 写入，任何服务端返回的字符串
   * （标题、文件名、失败代码）都不会被当作 HTML 解析。
   */
  function renderDocument(documentBody) {
    var box = element('knowledge-doc-body');
    if (!box) {
      return;
    }
    box.replaceChildren();

    appendRow(box, '文档 ID', documentBody.id);
    appendRow(box, '标题', documentBody.title);
    appendRow(box, '原始文件名', documentBody.originalFilename);
    appendRow(box, '格式', documentBody.format);
    appendRow(box, '媒体类型', documentBody.mediaType);
    appendRow(box, '字节数', isFiniteNumber(documentBody.sizeBytes) ? String(documentBody.sizeBytes) : null);
    appendRow(box, 'SHA-256', documentBody.sha256);
    appendRow(box, '状态', labelOfStatus(documentBody.status));
    appendRow(box, '版本', isFiniteNumber(documentBody.version) ? String(documentBody.version) : null);
    appendRow(box, '创建时间', documentBody.createdAt);
    appendRow(box, '更新时间', documentBody.updatedAt);
    appendRow(box, '解析完成时间', documentBody.parsedAt);
    appendRow(box, '索引完成时间', documentBody.indexedAt);
    appendRow(box, '嵌入服务提供方', documentBody.embeddingProvider);
    appendRow(box, '嵌入模型', documentBody.embeddingModel);
    appendRow(
      box,
      '向量维度',
      isFiniteNumber(documentBody.embeddingDimensions) ? String(documentBody.embeddingDimensions) : null
    );

    // 明确告知：文档 GET 没有 ETag，页面显示的版本只是快照，真正写入前还要重新核对。
    var note = document.createElement('p');
    note.className = 'hint';
    note.textContent =
      '该查询接口不返回 ETag；上面的版本号只是本次查询的快照。' +
      '点击「解析」时会先重新查询并核对版本，再据此构造 If-Match。';
    box.appendChild(note);

    show(element('knowledge-doc'), true);
    renderParseArea(documentBody);
  }

  /**
   * 只更新元数据展示的刷新路径（冲突后重新拉取）。
   *
   * <p><b>刻意不写 {@code currentDocument}</b>：刷新拿回来的版本可能<b>又</b>被第三方改过，
   * 一旦写进可写目标，就等于用「刚刷新到的版本」重新武装了写路径 ——
   * 而这条刷新本身并不是用户发起的核对。因此这里只做只读展示，
   * 写目标保持为空，用户必须重新「按 ID 查询」才会重新获得可写的目标。</p>
   */
  function renderDocumentFromRefresh(documentBody) {
    var box = element('knowledge-doc-body');
    if (!box) {
      return;
    }
    box.replaceChildren();

    appendRow(box, '文档 ID', documentBody.id);
    appendRow(box, '标题', documentBody.title);
    appendRow(box, '原始文件名', documentBody.originalFilename);
    appendRow(box, '格式', documentBody.format);
    appendRow(box, '媒体类型', documentBody.mediaType);
    appendRow(box, '字节数', isFiniteNumber(documentBody.sizeBytes) ? String(documentBody.sizeBytes) : null);
    appendRow(box, 'SHA-256', documentBody.sha256);
    appendRow(box, '状态', labelOfStatus(documentBody.status));
    appendRow(box, '版本', isFiniteNumber(documentBody.version) ? String(documentBody.version) : null);
    appendRow(box, '创建时间', documentBody.createdAt);
    appendRow(box, '更新时间', documentBody.updatedAt);
    appendRow(box, '解析完成时间', documentBody.parsedAt);
    appendRow(box, '索引完成时间', documentBody.indexedAt);
    appendRow(box, '嵌入服务提供方', documentBody.embeddingProvider);
    appendRow(box, '嵌入模型', documentBody.embeddingModel);

    var note = document.createElement('p');
    note.className = 'hint';
    note.textContent =
      '这是冲突后自动刷新的只读快照（不代表可写目标已重置）。' +
      '要再次写入，请重新「按 ID 查找」以取得可写目标。';
    box.appendChild(note);

    show(element('knowledge-doc'), true);
    // 不调用 renderParseArea：解析区的可写性由锁定状态与显式查询决定，
    // 刷新只读快照不得改变按钮的可写语义。
    setDisabled(element('knowledge-parse'), true);
  }

  // ---- 解析区 ---------------------------------------------------------------

  /**
   * 渲染解析区。注意：当解析区处于锁定状态时，这里只更新展示，绝不重新点亮按钮 ——
   * 刷新只读元数据不能把写路径重新打开。
   */
  function renderParseArea(documentBody) {
    var button = element('knowledge-parse');
    var note = element('knowledge-parse-note');
    var parsable = PARSABLE_STATUSES.indexOf(documentBody.status) >= 0;

    if (parseLocked) {
      setDisabled(button, true);
      return;
    }

    setDisabled(button, !parsable || parsing);
    if (parsable) {
      setText(
        note,
        '当前状态 ' + documentBody.status + '：可以点击「解析」。解析前会重新查询核对版本，' +
          '不会使用写死的版本号。'
      );
    } else {
      setText(
        note,
        '当前状态 ' + documentBody.status + '：该状态下不允许解析，按钮已禁用。' +
          '（服务端对不允许的解析会返回 409。）'
      );
    }
  }

  function renderParseResult(parsed) {
    var box = element('knowledge-parse-result');
    if (!box) {
      return;
    }
    box.replaceChildren();

    appendRow(box, '文档 ID', parsed.documentId);
    appendRow(box, '标题', parsed.title);
    appendRow(box, '状态', labelOfStatus(parsed.status));
    appendRow(box, '版本', isFiniteNumber(parsed.version) ? String(parsed.version) : null);
    appendRow(box, '切片数', isFiniteNumber(parsed.chunkCount) ? String(parsed.chunkCount) : null);
    appendRow(box, '解析完成时间', parsed.parsedAt);
    if (parsed.failureCode) {
      appendRow(box, '失败代码', parsed.failureCode + '（' + labelOfFailure(parsed.failureCode) + '）');
    }
    show(box, true);
  }

  function clearParseResult() {
    var box = element('knowledge-parse-result');
    if (box) {
      box.replaceChildren();
      show(box, false);
    }
  }

  /**
   * 渲染解析失败的错误体（{@code application/problem+json}）。
   *
   * <p><b>为什么必须单独渲染</b>：422 / 413 的响应体是 problem ——
   * 它的字段是 {@code type/title/status/detail/instance/code/failureCode}，
   * 而 {@code ParsedDocumentResponse} 的字段是
   * {@code documentId/title/status/version/chunkCount/parsedAt/failureCode}。
   * 两者都有 {@code title}、{@code status}、{@code failureCode}，字段名重叠但语义完全不同：
   * 直接按解析结果渲染会把「文档无法解析」这个错误标题当成文档标题，
   * 并凭空显示出空的「切片数」「解析完成时间」，把一个错误体伪装成文档快照。</p>
   *
   * <p>这里只展示服务端给出的<b>固定安全文案</b>（title + detail）与稳定枚举
   * {@code failureCode}，不做任何推断与拼接。</p>
   */
  function renderParseProblem(status, problemBody) {
    var box = element('knowledge-parse-result');
    if (!box) {
      return;
    }
    box.replaceChildren();

    var heading = document.createElement('p');
    heading.className = 'failed';
    heading.textContent = '解析失败（HTTP ' + status + '）——这是错误响应，不是解析结果。';
    box.appendChild(heading);

    var body = problemBody && typeof problemBody === 'object' ? problemBody : {};
    appendRow(box, '错误标题', typeof body.title === 'string' ? body.title : null);
    appendRow(box, '错误说明', typeof body.detail === 'string' ? body.detail : null);
    appendRow(box, '错误码', typeof body.code === 'string' ? body.code : null);
    if (typeof body.failureCode === 'string' && body.failureCode.length > 0) {
      appendRow(box, '失败代码', labelOfFailure(body.failureCode) + '（' + body.failureCode + '）');
    }

    var hint = document.createElement('p');
    hint.className = 'hint';
    hint.textContent =
      '该响应是 problem+json 错误体，不含切片数与解析完成时间。' +
      '文档通常已进入 PARSE_FAILED 状态、版本也已变化，请重新按 ID 查询后再决定下一步。';
    box.appendChild(hint);

    show(box, true);
  }

  // ---- 上传 ----------------------------------------------------------------

  function setUploadBusy(busy) {
    uploading = busy;
    setDisabled(element('knowledge-upload'), busy);
    setDisabled(element('knowledge-upload-submit'), busy);
  }

  function renderUploadedId(documentBody) {
    var box = element('knowledge-upload-result');
    if (!box) {
      return;
    }
    box.replaceChildren();

    var heading = document.createElement('p');
    heading.className = 'ok';
    heading.textContent = '上传成功（HTTP 201）。上传响应不含 ETag，请按文档 ID 继续操作。';
    box.appendChild(heading);

    var idValue = appendRow(box, '文档 ID', documentBody.id);
    idValue.classList.add('knowledge-id');

    var copy = document.createElement('button');
    copy.type = 'button';
    copy.id = 'knowledge-copy-id';
    copy.textContent = '复制文档 ID';
    copy.addEventListener('click', function () {
      copyDocumentId(documentBody.id);
    });
    box.appendChild(copy);

    var feedback = document.createElement('span');
    feedback.id = 'knowledge-copy-feedback';
    feedback.className = 'state ok';
    feedback.hidden = true;
    box.appendChild(feedback);

    appendRow(box, '标题', documentBody.title);
    appendRow(box, '格式', documentBody.format);
    appendRow(box, '状态', labelOfStatus(documentBody.status));
    appendRow(box, '版本', isFiniteNumber(documentBody.version) ? String(documentBody.version) : null);

    var hint = document.createElement('p');
    hint.className = 'hint';
    hint.textContent = '可以把这个文档 ID 填到上面的「按 ID 查找」框里，再查看元数据并解析。';
    box.appendChild(hint);

    show(box, true);
  }

  function clearUploadResult() {
    var box = element('knowledge-upload-result');
    if (box) {
      box.replaceChildren();
      show(box, false);
    }
  }

  function showCopyFeedback(message) {
    var node = element('knowledge-copy-feedback');
    if (!node) {
      return;
    }
    node.textContent = message;
    setClass(node, 'state ok');
    show(node, true);
    if (copyTimer !== null) {
      window.clearTimeout(copyTimer);
    }
    copyTimer = window.setTimeout(function () {
      copyTimer = null;
      show(node, false);
    }, 4000);
  }

  function copyDocumentId(id) {
    // 本机回环地址在浏览器里通常被视为可信来源，但不同版本行为不完全一致，
    // 因此这里做两级兜底：剪贴板 API 不可用或写入被拒时，退回只读文本域 + execCommand。
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      navigator.clipboard.writeText(id).then(function () {
        showCopyFeedback('已复制到剪贴板。');
      }, function () {
        fallbackCopy(id);
      });
      return;
    }
    fallbackCopy(id);
  }

  function fallbackCopy(id) {
    var input = document.createElement('input');
    input.type = 'text';
    input.value = id;
    input.setAttribute('readonly', 'readonly');
    input.className = 'knowledge-copy-fallback';
    var host = element('knowledge-upload-result');
    if (host) {
      host.appendChild(input);
    }
    input.select();
    var copied = false;
    try {
      copied = document.execCommand('copy');
    } catch (error) {
      copied = false;
    }
    if (host) {
      host.removeChild(input);
    }
    if (copied) {
      showCopyFeedback('已复制到剪贴板。');
    } else {
      showCopyFeedback('复制失败：请手动选中上面的文档 ID 复制。');
    }
  }

  function submitUpload(event) {
    event.preventDefault();
    if (uploading) {
      return;
    }

    var titleInput = element('knowledge-title');
    var fileInput = element('knowledge-file');
    if (!titleInput || !fileInput) {
      return;
    }

    var title = titleInput.value;
    var file = fileInput.files && fileInput.files.length > 0 ? fileInput.files[0] : null;

    clearUploadResult();
    setKnowledgeError('');

    // 前置校验只为了少发一次注定失败的请求；服务端仍是最终校验者。
    if (title.length === 0) {
      setKnowledgeError('请先填写标题。（客户端提示，服务端同样会以 400 拒绝。）');
      return;
    }
    if (file === null) {
      setKnowledgeError('请先选择文件。（客户端提示，服务端同样会以 400 拒绝。）');
      return;
    }

    var form = new FormData();
    form.append('title', title);
    form.append('file', file, file.name);

    setUploadBusy(true);
    setKnowledgeState('pending', '正在上传…');

    guard(requestJson(DOCUMENTS_PATH, {
      method: 'POST',
      headers: { Accept: 'application/json' },
      body: form
    }), function () {
      setUploadBusy(false);
    }, function () {
      setKnowledgeState('failed', '结果待确认，请刷新');
      setKnowledgeError('上传请求在页面内异常终止，无法确认服务端是否已经创建文档。请刷新页面后按 ID 查找确认，不要直接重试。');
    }).then(function (result) {
      if (result === null) {
        return; // 已由 onCrash 收尾
      }

      if (result.bodyReadFailed) {
        // 拿到了 201 响应头但读不到响应体：文档很可能已创建，但拿不到它的 ID。
        setKnowledgeState('failed', '结果待确认，请刷新');
        setKnowledgeError(
          '上传返回 HTTP ' + result.status +
            ' 但读取响应体失败：服务端可能已经创建了文档，只是客户端没能读到结果。' +
            '请刷新页面后按 ID 查找确认，不要直接重试（重试可能会重复创建文档）。'
        );
        return;
      }

      if (result.transportError) {
        setKnowledgeState('failed', '结果待确认，请刷新');
        setKnowledgeError('上传请求网络中断，无法确认服务端是否已经创建文档。请刷新页面后按 ID 查找确认，不要直接重试。');
        return;
      }

      if (result.status === 201) {
        if (result.formatProblem !== null || result.body === null) {
          setKnowledgeState('failed', '结果待确认，请刷新');
          setKnowledgeError('上传返回 201，但响应体无法解析：' + result.formatProblem + '请换个方式确认，不要盲目重试。');
          return;
        }
        if (missingField(result.body, 'id')) {
          setKnowledgeState('failed', '结果待确认，请刷新');
          setKnowledgeError('上传返回 201，但响应体里没有文档 ID，无法确认创建结果。请勿直接重试。');
          return;
        }
        clearKnowledgeState();
        renderUploadedId(result.body);
        return;
      }

      if (result.status === 413) {
        setKnowledgeState('failed', '上传被拒绝');
        setKnowledgeError(
          'HTTP 413：文件超过服务端上限（当前提示为约 ' + MAX_UPLOAD_MB +
            ' MB）。请压缩或拆分后重试。服务端始终是最终校验者。'
        );
        return;
      }

      if (result.status === 415) {
        setKnowledgeState('failed', '上传被拒绝');
        setKnowledgeError(
          'HTTP 415：文件格式或内容不受支持。支持 ' + FORMAT_HINTS.join(' / ') + '；请确认扩展名与实际内容一致。'
        );
        return;
      }

      if (result.status === 400) {
        setKnowledgeState('failed', '上传被拒绝');
        setKnowledgeError(describeProblem(result.status, result.body));
        return;
      }

      if (result.status >= 500) {
        setKnowledgeState('failed', '结果待确认，请刷新');
        setKnowledgeError(
          describeProblem(result.status, result.body) + '服务端错误，结果待确认。请刷新页面后按 ID 查找确认，不要直接重试。'
        );
        return;
      }

      setKnowledgeState('failed', '上传失败');
      setKnowledgeError(describeProblem(result.status, result.body));
    });
  }

  // ---- 按 ID 查询 -----------------------------------------------------------

  function submitLookup(event) {
    event.preventDefault();
    lookupDocument();
  }

  function lookupDocument() {
    var input = element('knowledge-lookup');
    if (!input) {
      return;
    }
    var raw = input.value;
    var id = raw.replace(/^\s+|\s+$/g, '');

    // 用户显式发起查询 = 唯一解锁入口：先解锁，再看这次查询的结果。
    unlockParseArea();
    clearDocumentView();
    clearParseResult();
    clearKnowledgeState();

    if (id.length === 0) {
      parseLocked = true;
      setDisabled(element('knowledge-parse'), true);
      setKnowledgeError('请输入要查找的文档 ID。（服务端对非规范的 ID 会返回 400。）');
      return;
    }

    lookupToken += 1;
    var token = lookupToken;
    setDisabled(element('knowledge-lookup-submit'), true);
    setKnowledgeState('pending', '正在查询文档 ' + id + ' …');

    requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(id), {
      headers: { Accept: 'application/json' }
    }).then(function (result) {
      if (token !== lookupToken) {
        return; // 已经有更新的查询，丢弃这次结果
      }
      setDisabled(element('knowledge-lookup-submit'), false);

      // 读 body 失败：查询是只读的，没有「服务端可能已经改了」的风险，
      // 但同样必须给出明确结果，而不是让按钮永久停在禁用态。
      if (result.bodyReadFailed) {
        setKnowledgeState('failed', '查询失败');
        setKnowledgeError(
          '查询返回 HTTP ' + result.status + ' 但读取响应体失败，无法确认该文档的当前状态。请稍后重试。'
        );
        return;
      }

      if (result.transportError) {
        setKnowledgeState('failed', '查询失败');
        setKnowledgeError('网络中断：无法确认服务端状态。请检查本地服务是否在运行后重试。');
        return;
      }

      if (result.status === 404) {
        setKnowledgeState('failed', '未找到');
        setKnowledgeError('HTTP 404：没有该文档 ID 对应的文档。请核对文档 ID 是否完整、是否属于本服务。');
        return;
      }

      if (result.status === 400) {
        setKnowledgeState('failed', '查询被拒绝');
        setKnowledgeError(
          'HTTP 400：文档 ID 不是规范的 36 位 UUID。' +
            (problemCode(result.body) ? '（' + problemCode(result.body) + '）' : '')
        );
        return;
      }

      if (result.status >= 500) {
        setKnowledgeState('failed', '查询失败');
        setKnowledgeError(describeProblem(result.status, result.body));
        return;
      }

      if (result.status !== 200) {
        setKnowledgeState('failed', '查询失败');
        setKnowledgeError(describeProblem(result.status, result.body));
        return;
      }

      if (result.formatProblem !== null || result.body === null) {
        setKnowledgeState('failed', '查询失败');
        setKnowledgeError('响应体无法解析：' + result.formatProblem);
        return;
      }

      if (missingField(result.body, 'id') || missingField(result.body, 'version') || missingField(result.body, 'status')) {
        setKnowledgeState('failed', '查询失败');
        setKnowledgeError('响应体缺少 id / version / status 中的必要字段，无法安全地继续解析流程。');
        return;
      }

      // 核对返回的文档 ID 与用户输入一致，避免把别的文档当成目标。
      if (String(result.body.id).toLowerCase() !== id.toLowerCase()) {
        setKnowledgeState('failed', '查询失败');
        setKnowledgeError('服务端返回的文档 ID 与请求的不一致，出于安全考虑已停止后续操作。');
        return;
      }

      currentDocument = {
        id: result.body.id,
        version: result.body.version,
        status: result.body.status
      };
      clearKnowledgeState();
      renderDocument(result.body);
    }, function () {
      // 查询在页面内异常终止：必须恢复可点状态，否则用户再也查不了。
      setDisabled(element('knowledge-lookup-submit'), false);
      setKnowledgeState('failed', '查询失败');
      setKnowledgeError('查询在页面内异常终止，未能取得文档状态。请稍后重试。');
    });
  }

  // ---- 解析 ----------------------------------------------------------------

  function setParseBusy(busy) {
    parsing = busy;
    var button = element('knowledge-parse');
    if (button && currentDocument) {
      setDisabled(button, busy || PARSABLE_STATUSES.indexOf(currentDocument.status) < 0);
    } else {
      setDisabled(button, busy);
    }
    setDisabled(element('knowledge-lookup-submit'), busy);
  }

  /**
   * 解析失败后的统一收口：锁定解析区，要求用户重新查询。
   * 不提供任何自动重试。锁定的清除只发生在用户重新「按 ID 查询」时。
   */
  function lockParse(message) {
    parsing = false;
    parseLocked = true;
    currentDocument = null;
    setDisabled(element('knowledge-parse'), true);
    setText(element('knowledge-parse-note'), '解析区已锁定：' + message + '请重新按 ID 查询后再决定下一步。');
    setKnowledgeError(message + '（写请求的结果未知或未生效，页面不会自动重试。）');
  }

  function parseDocument() {
    if (parsing || parseLocked || !currentDocument) {
      return;
    }
    var targetId = currentDocument.id;
    var displayedVersion = currentDocument.version;
    var displayedStatus = currentDocument.status;

    clearParseResult();
    setKnowledgeError('');
    setParseBusy(true);
    setKnowledgeState('pending', '正在重新核对文档版本…');

    // 第一步：重新 GET。文档 GET 不返回 ETag，因此版本只来自响应体。
    guard(requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(targetId), {
      headers: { Accept: 'application/json' }
    }), function () {
      setParseBusy(false);
    }, function () {
      lockParse('写请求未发送：解析前的版本核对在页面内异常终止，无法确认文档当前状态。');
    }).then(function (probe) {
      if (probe === null) {
        return; // 已由 onCrash 收尾并锁定
      }

      if (probe.bodyReadFailed) {
        // 核对请求是只读的，但读不到版本就无法安全构造 If-Match —— 此时**不发**写请求。
        lockParse(
          '写请求未发送：解析前核对返回 HTTP ' + probe.status +
            ' 但读取响应体失败，无法取得当前版本，因此没有发送解析请求。'
        );
        return;
      }
      if (probe.transportError) {
        lockParse('写请求未发送：解析前的版本核对请求网络中断，无法确认文档当前状态。');
        return;
      }
      if (probe.status === 404) {
        lockParse('写请求未发送：解析前核对时该文档已不存在（HTTP 404）。');
        return;
      }
      if (probe.status !== 200) {
        lockParse('写请求未发送：解析前核对返回 HTTP ' + probe.status + '，无法确认文档当前状态。');
        return;
      }
      if (probe.formatProblem !== null || probe.body === null) {
        lockParse('写请求未发送：解析前核对的响应体无法解析（' + probe.formatProblem + '）。');
        return;
      }
      if (missingField(probe.body, 'id') || missingField(probe.body, 'version') || missingField(probe.body, 'status')) {
        lockParse('写请求未发送：解析前核对的响应体缺少 id / version / status。');
        return;
      }
      if (String(probe.body.id).toLowerCase() !== targetId.toLowerCase()) {
        lockParse('写请求未发送：解析前核对返回的文档 ID 与目标不一致。');
        return;
      }

      // 漂移检查：用户看到的版本/状态若与服务端当前不一致，就刷新展示并锁定，
      // 绝不拿陈旧版本去写。刷新走 renderDocumentFromRefresh：只更新展示，
      // 不重建可写目标（见 FD-0023-D-R1 约束 11）。
      if (probe.body.version !== displayedVersion) {
        renderDocumentFromRefresh(probe.body);
        lockParse(
          '写请求未发送：文档版本已从 ' + displayedVersion + ' 变为 ' + probe.body.version +
            '（页面已刷新为最新版本）。'
        );
        return;
      }
      if (probe.body.status !== displayedStatus) {
        renderDocumentFromRefresh(probe.body);
        lockParse(
          '写请求未发送：文档状态已从 ' + displayedStatus + ' 变为 ' + probe.body.status + '（页面已刷新为最新状态）。'
        );
        return;
      }
      if (PARSABLE_STATUSES.indexOf(probe.body.status) < 0) {
        renderDocumentFromRefresh(probe.body);
        lockParse('写请求未发送：当前状态 ' + probe.body.status + ' 不允许解析。');
        return;
      }

      // 版本已核对通过：由它构造带双引号的 If-Match，绝不写死 "0"。
      var requestETag = versionTagOf(probe.body);
      if (!isStrongETag(requestETag)) {
        lockParse('写请求未发送：无法由版本号构造合法的强 ETag。');
        return;
      }

      setKnowledgeState('pending', '正在解析（If-Match: ' + requestETag + '）…');
      sendParseRequest(targetId, displayedStatus, requestETag);
    });
  }

  function sendParseRequest(targetId, displayedStatus, requestETag) {
    guard(requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(targetId) + '/parse', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'If-Match': requestETag
      }
    }), function () {
      setParseBusy(false);
    }, function () {
      // 写请求在页面内异常终止：同样属于「结果待确认」，必须锁住写路径。
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse('解析请求在页面内异常终止，无法确认服务端是否已经改变了文档状态。');
    }).then(function (result) {
      if (result === null) {
        return; // 已由 onCrash 收尾并锁定
      }
      handleParseResult(targetId, displayedStatus, requestETag, result);
    });
  }

  function handleParseResult(targetId, displayedStatus, requestETag, result) {
    // 拿到了响应头但读不到 body：服务端**很可能已经**解析完成（或已记失败），
    // 但客户端拿不到权威版本号。这正是「结果待确认」，必须锁住写路径且不自动重试。
    if (result.bodyReadFailed) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse(
        '解析返回 HTTP ' + result.status +
          ' 但读取响应体失败，无法确认文档是否已解析成功或已进入失败状态。'
      );
      return;
    }

    if (result.transportError) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse('解析请求网络中断，无法确认服务端是否已经改变了文档状态。');
      return;
    }

    // 412：版本冲突，说明页面上的版本不是最新的。刷新展示并锁定。
    if (result.status === 412) {
      setKnowledgeState('failed', '版本冲突');
      lockParse('解析被拒绝（HTTP 412 版本冲突）：文档在核对之后又被改动过，If-Match ' + requestETag + ' 已失效。');
      refreshAfterConflict(targetId);
      return;
    }

    // 428：请求没带 If-Match。本页不会出现这种请求，如实提示而不是掩盖。
    if (result.status === 428) {
      setKnowledgeState('failed', '缺少前置条件');
      lockParse('解析被拒绝（HTTP 428）：服务端认为请求缺少 If-Match 头。这属于本页异常，请重新查询后再试。');
      return;
    }

    // 409：状态不允许解析（例如已被别处解析成 PARSED）。
    if (result.status === 409) {
      setKnowledgeState('failed', '状态不允许');
      lockParse(
        '解析被拒绝（HTTP 409）：文档当前状态不允许解析，页面记录的 ' + displayedStatus +
          ' 已经过期。请重新查询查看最新状态。'
      );
      refreshAfterConflict(targetId);
      return;
    }

    if (result.status === 400) {
      setKnowledgeState('failed', '请求被拒绝');
      setKnowledgeError(
        describeProblem(result.status, result.body) +
          '（HTTP 400：可能是 If-Match 格式非法。页面不会自动重试。）'
      );
      return;
    }

    if (result.status === 404) {
      setKnowledgeState('failed', '未找到');
      lockParse('解析返回 HTTP 404：该文档已不存在。');
      return;
    }

    if (result.status === 422 || result.status === 413) {
      // 解析/切片失败：响应体是 problem+json（不是 ParsedDocumentResponse），
      // 文档已进入 PARSE_FAILED 且版本已变化，必须刷新展示。
      setKnowledgeState('failed', '解析失败');
      if (result.body !== null && result.formatProblem === null) {
        renderParseProblem(result.status, result.body);
      }
      setKnowledgeError(
        describeProblem(result.status, result.body) +
          '文档已进入失败状态，页面显示的版本已过期，请重新查询确认。'
      );
      refreshAfterConflict(targetId);
      return;
    }

    if (result.status >= 500) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse(
        '解析返回 HTTP ' + result.status + '（服务端错误），无法确认文档是否已改变。' +
          '服务端提示：' + describeProblem(result.status, result.body)
      );
      return;
    }

    if (result.status !== 200) {
      setKnowledgeState('failed', '解析失败');
      setKnowledgeError(describeProblem(result.status, result.body));
      return;
    }

    // 成功：必须校验响应体与 ETag 自洽，否则不认。
    if (result.formatProblem !== null || result.body === null) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse('解析返回 200，但响应体无法解析（' + result.formatProblem + '）。');
      return;
    }
    if (missingField(result.body, 'documentId') || missingField(result.body, 'version') || missingField(result.body, 'status')) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse('解析返回 200，但响应体缺少 documentId / version / status。');
      return;
    }
    if (!isStrongETag(result.eTag)) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse('解析返回 200，但响应没有可用的强 ETag，无法确认版本。');
      return;
    }
    if (result.eTag !== versionTagOf(result.body)) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse('解析返回 200，但 ETag ' + result.eTag + ' 与响应体版本 ' + result.body.version + ' 不一致。');
      return;
    }
    if (String(result.body.documentId).toLowerCase() !== targetId.toLowerCase()) {
      setKnowledgeState('failed', '结果待确认，请刷新');
      lockParse('解析返回 200，但响应体里的文档 ID 与请求的不一致。');
      return;
    }

    clearKnowledgeState();
    renderParseResult(result.body);

    // 用权威结果更新页面上的版本，不假定解析只加 1。
    currentDocument = {
      id: result.body.documentId,
      version: result.body.version,
      status: result.body.status
    };
    setParseBusy(false);
    renderParseArea(result.body);

    var hint = element('knowledge-parse-note');
    if (hint) {
      hint.textContent =
        '解析完成：版本已由服务端从 ' + requestETag + ' 推进到 ' + result.eTag +
        '（响应头 ETag 与响应体版本一致）。当前状态 ' + result.body.status + '。';
    }
  }

  /**
   * 版本/状态冲突后重新拉取一次展示，让页面与真实状态对齐（只读，不改数据）。
   *
   * <p>这里**只更新展示**：不写 {@code currentDocument}、不碰解析区按钮。
   * 刷新前「不得用旧版本再次写入」这条规则，靠的是「锁住时写目标为空」这个显式不变量，
   * 而不是靠「锁定标志恰好还在」这种巧合。</p>
   */
  function refreshAfterConflict(targetId) {
    lookupToken += 1;
    var token = lookupToken;
    requestJson(DOCUMENTS_PATH + '/' + encodeURIComponent(targetId), {
      headers: { Accept: 'application/json' }
    }).then(function (result) {
      if (token !== lookupToken) {
        return;
      }
      // 读 body 失败或任何异常：保留原有错误提示，不覆盖、也不改变可写状态。
      if (result.transportError || result.bodyReadFailed || result.status !== 200 || result.body === null ||
          missingField(result.body, 'id') || missingField(result.body, 'version') || missingField(result.body, 'status')) {
        return;
      }
      renderDocumentFromRefresh(result.body);
    }, function () {
      // 刷新失败不影响既有锁定与提示。
    });
  }

  // ---- 事件绑定（全部为点击/提交触发，加载期不发起任何请求） ----------------

  function bindKnowledge() {
    var uploadForm = element('knowledge-upload');
    if (uploadForm) {
      uploadForm.addEventListener('submit', submitUpload);
    }
    var lookupForm = element('knowledge-lookup-form');
    if (lookupForm) {
      lookupForm.addEventListener('submit', submitLookup);
    }
    var parseButton = element('knowledge-parse');
    if (parseButton) {
      parseButton.addEventListener('click', parseDocument);
    }
    renderStaticHints();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindKnowledge);
  } else {
    bindKnowledge();
  }
})();
