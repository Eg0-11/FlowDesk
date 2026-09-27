/**
 * FlowDesk 知识检索（FD-0023-F）——只接既有 POST /api/v1/knowledge/search。
 *
 * <p>本文件与 knowledge.js / app.js 互不影响：不共享状态、不改后端 API、
 * 页面加载时<b>只绑定事件</b>，不发任何请求（加载、上传、解析或索引完成都绝不自动检索）。</p>
 *
 * <h2>不变量</h2>
 * <ol>
 * <li><b>费用确认是一次性授权</b>：每次检索都要单独勾选；点击「检索」通过守卫后确认即被消耗
 *     （授权标志置回 false 且勾选框同步清空），想再检索必须重新勾选。
 *     在途请求不读确认状态，发起后勾选框怎么变都不影响已在途的那一次。</li>
 * <li><b>请求只走 JSON body</b>：问题（query）只放在请求体里，不写入 URL、
 *     不进入任何浏览器持久存储。topK / minScore 留空时
 *     <b>不下发该字段</b>，由服务端套用默认值 —— 页面不硬编码任何默认参数。</li>
 * <li><b>在途禁用重复提交，绝不自动重试</b>：请求在途时按钮禁用；任何失败都不触发重试，
 *     重新检索永远是用户显式动作（重新勾选费用确认后再点击）。</li>
 * <li><b>结果区分两类失败</b>：400（服务端在校验阶段拒绝，未调用向量化服务，未产生费用）与
 *     Basic 模式 503 KNOWLEDGE_EMBEDDING_DISABLED（未启用向量化，检索未执行，未产生费用）
 *     是<b>确定结果</b>；502 / 其它 5xx / 网络中断 / 读体失败时请求已经发出，
 *     <b>可能已产生 Query Embedding（或重排）费用但结果未取得</b> —— 提示用户不要盲目重试。</li>
 * <li><b>200 也必须自洽才展示</b>：渲染前校验<b>实际展示字段</b>的类型与取值（FD-0023-F-R1）——
 *     生效参数必须合法（provider / model 非空字符串、dimensions 与 topK 正整数、minScore 有限数字）、
 *     {@code rankingMode} 只能是既有的两种模式（VECTOR_SIMILARITY / RERANK）、引用必须按返回顺序
 *     对应<b>连续</b>的 K1… 与 rank=1…、分数必须是有限数字；RERANK 必须带 rerankModel 且每条引用
 *     都有重排分，VECTOR_SIMILARITY 不得带这两类重排字段。任何错配都显示「结果不完整」，
 *     不渲染为成功、不本地重排或猜测修正；200 + 空 citations 是正常的「无命中」，不是失败。
 *     畸形数据边界（FD-0023-F-R2）：citations 里的 null / 数组 / 非对象元素同样按「结果不完整」
 *     处理，绝不抛页面异常或把状态留在「正在检索」；并按后端既有契约校验取值范围 ——
 *     topK ∈ 1..20、minScore ∈ 0..1、引用条数不超过生效 topK、相似度 ∈ [minScore, 1]
 *     （重排分是当前请求内的相对分，保持既有语义、不做范围校验）。</li>
 * <li><b>渲染只走 textContent / replaceChildren</b>：服务端返回的任何字符串（标题、正文…）
 *     都不会作为 HTML 解析。相似度（score）与重排分（rerankScore）是两个不同的分数，
 *     分开展示且不互相换算。</li>
 * </ol>
 */
(function () {
  'use strict';

  var SEARCH_PATH = '/api/v1/knowledge/search';

  // 检索请求在途标志。
  var searching = false;

  // 费用确认的「本次授权」标志：勾选框负责置位，检索一旦提交（通过守卫后）就消耗。
  var searchArmed = false;

  // ---- 通用小工具（与 knowledge.js 同款，独立副本以免两个脚本互相依赖） ------

  function element(id) {
    return document.getElementById(id);
  }

  function setText(node, text) {
    if (node) {
      node.textContent = text;
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

  function isFiniteNumber(value) {
    return typeof value === 'number' && isFinite(value);
  }

  /** 非空字符串（展示字段的最小类型要求）。 */
  function isNonEmptyString(value) {
    return typeof value === 'string' && value.length > 0;
  }

  /** 有限整数且不小于给定下限（版本、切片序号、条数上限等取值校验用）。 */
  function isIntegerAtLeast(value, min) {
    return isFiniteNumber(value) && value === Math.floor(value) && value >= min;
  }

  /** problem+json 的 code；body 为 null / 非 JSON / 缺 code 时返回空串。 */
  function problemCode(body) {
    if (body && typeof body === 'object' && typeof body.code === 'string') {
      return body.code;
    }
    return '';
  }

  /**
   * 把 problem+json 的 title/detail 组合成一句可读的失败说明。
   * 服务端的 detail 已经过脱敏，这里原样展示，不做二次拼接推断。
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

  /**
   * 统一的请求包装：**永不拒绝**。返回 { status, body, eTag, transportError,
   * bodyReadFailed, formatProblem }，三种失败语义互不重叠（与 knowledge.js 同款）。
   */
  function requestJson(url, options) {
    var settings = options || {};
    var init = { method: settings.method || 'GET', headers: settings.headers || {} };
    if (settings.body !== undefined) {
      init.body = settings.body;
    }
    return fetch(url, init).then(function (response) {
      var status = response.status;
      var ok = response.ok;
      var readBody = response.text().then(function (text) {
        return { text: text, failed: false };
      }, function () {
        return { text: '', failed: true };
      });
      return readBody.then(function (outcome) {
        if (outcome.failed) {
          return { status: status, body: null, eTag: null, transportError: false, bodyReadFailed: true, formatProblem: null };
        }
        var text = outcome.text;
        if (text.length === 0) {
          return {
            status: status, body: null, eTag: null, transportError: false, bodyReadFailed: false,
            formatProblem: ok ? '响应体为空，无法确认结果。' : null
          };
        }
        var parsed;
        try {
          parsed = JSON.parse(text);
        } catch (error) {
          return {
            status: status, body: null, eTag: null, transportError: false, bodyReadFailed: false,
            formatProblem: ok ? '响应体不是合法 JSON，无法确认结果。' : null
          };
        }
        if (ok && (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed))) {
          return {
            status: status, body: null, eTag: null, transportError: false, bodyReadFailed: false,
            formatProblem: '响应体不是 JSON 对象，无法确认结果。'
          };
        }
        return { status: status, body: parsed, eTag: null, transportError: false, bodyReadFailed: false, formatProblem: null };
      });
    }, function () {
      return { status: 0, body: null, eTag: null, transportError: true, bodyReadFailed: false, formatProblem: null };
    });
  }

  /**
   * 无论成功失败都执行的收尾：复位忙标志与按钮。promise 拒绝（页面内异常）时
   * 额外走 onCrash 给出明确提示 —— 绝不留下未处理的 Promise 拒绝或永久禁用的按钮。
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

  // ---- 状态区渲染 ------------------------------------------------------------

  function setSearchState(kind, message) {
    var node = element('knowledge-search-state');
    if (!node) {
      return;
    }
    node.textContent = message;
    node.className = 'state ' + kind;
    show(node, true);
  }

  function setSearchError(message) {
    var node = element('knowledge-search-error');
    if (!node) {
      return;
    }
    node.textContent = message;
    show(node, message !== '');
  }

  // ---- 费用确认（一次性授权，FD-0023-E-R1 同款不变量） ------------------------

  /**
   * 本次费用确认授权是否仍然有效。读授权标志而不是勾选框本身：
   * 提交时勾选框会被程序清空，「提交即消耗」不依赖 DOM 同步时序。
   */
  function isSearchConfirmed() {
    return searchArmed;
  }

  /** 费用确认的唯一写入口：同时维护授权标志与勾选框选中态，不产生两边不一致的窗口。 */
  function setSearchConfirmation(confirmed) {
    searchArmed = !!confirmed;
    var box = element('knowledge-search-confirm');
    if (box) {
      box.checked = searchArmed;
    }
  }

  /** 按当前状态重估检索按钮与提示。 */
  function refreshSearchButton() {
    setDisabled(element('knowledge-search-submit'), searching || !searchArmed);
    var note = element('knowledge-search-note');
    if (!note) {
      return;
    }
    if (!searchArmed) {
      setText(note, '每次检索都需要单独确认：请先勾选费用确认' +
        '（检索可能调用 Query Embedding 并产生费用；开启重排时还可能产生重排费用），再点击「检索」。');
      return;
    }
    setText(note, '已确认费用。本次确认只授权一次检索（点击「检索」后确认即被消耗，再次检索需要重新勾选）。');
  }

  function setSearchBusy(busy) {
    searching = busy;
    setDisabled(element('knowledge-search-submit'), busy || !searchArmed);
  }

  // ---- 结果渲染（全部 textContent / replaceChildren） -------------------------

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

  function rankingModeLabel(mode) {
    if (mode === 'RERANK') {
      return mode + '（顺序由重排分决定）';
    }
    if (mode === 'VECTOR_SIMILARITY') {
      return mode + '（顺序由向量相似度决定）';
    }
    return String(mode);
  }

  /**
   * 渲染一次成功的检索结果。引用按<b>服务端返回顺序</b>逐条展示（K1、K2……），
   * 不做任何本地重排；相似度与重排分是两个不同的分数，分开展示且不互相换算。
   */
  function renderSearchResult(body) {
    var box = element('knowledge-search-result');
    if (!box) {
      return;
    }
    box.replaceChildren();

    appendRow(box, '向量服务提供方', body.provider);
    appendRow(box, '向量模型', body.model);
    appendRow(box, '向量维度', isFiniteNumber(body.dimensions) ? String(body.dimensions) : null);
    appendRow(box, '生效 topK', isFiniteNumber(body.topK) ? String(body.topK) : null);
    appendRow(box, '生效 minScore（相似度下限，含边界）', isFiniteNumber(body.minScore) ? String(body.minScore) : null);
    appendRow(box, '排序模式', rankingModeLabel(body.rankingMode));
    if (typeof body.rerankModel === 'string' && body.rerankModel.length > 0) {
      appendRow(box, '重排模型', body.rerankModel);
    }

    var citations = body.citations;
    if (citations.length === 0) {
      var empty = document.createElement('p');
      empty.className = 'hint';
      empty.textContent = '无命中：知识库中没有相似度不低于生效 minScore 的内容。' +
        '这是正常结果，不是失败；检索本身已经完成。';
      box.appendChild(empty);
      show(box, true);
      return;
    }

    var heading = document.createElement('p');
    heading.className = 'hint';
    heading.textContent = '共 ' + citations.length + ' 条引用（按服务端返回顺序，K1、K2……）：';
    box.appendChild(heading);

    citations.forEach(function (citation) {
      var block = document.createElement('div');
      block.className = 'detail-row-group';
      var title = document.createElement('div');
      title.className = 'field-name';
      title.textContent = citation.citationId + '（名次 ' + citation.rank + '）';
      block.appendChild(title);
      appendRow(block, '文档标题', citation.documentTitle);
      appendRow(block, '文档版本', isFiniteNumber(citation.documentVersion) ? String(citation.documentVersion) : null);
      appendRow(block, '切片序号', isFiniteNumber(citation.chunkIndex) ? String(citation.chunkIndex) : null);
      appendRow(block, '正文', citation.content);
      appendRow(block, '相似度（向量余弦）', isFiniteNumber(citation.score) ? String(citation.score) : null);
      if (isFiniteNumber(citation.rerankScore)) {
        appendRow(block, '重排分（本次请求内的相对分，不与相似度混用）', String(citation.rerankScore));
      }
      box.appendChild(block);
    });
    show(box, true);
  }

  function clearSearchResult() {
    var box = element('knowledge-search-result');
    if (box) {
      box.replaceChildren();
      show(box, false);
    }
  }

  // ---- 结果处理 ---------------------------------------------------------------

  /**
   * 200 也必须自洽（FD-0023-F-R1 收紧类型与取值，FD-0023-F-R2 补畸形数据边界）：
   * 渲染前校验**实际展示字段**。
   *
   * <p>返回一个问题描述字符串（结果不完整、不渲染），或 null（可以渲染）：</p>
   * <ul>
   *   <li>生效参数合法：provider / model 非空字符串，dimensions 正整数，
   *       topK 是 1..20 之间的正整数（后端 {@code MAX_TOP_K_LIMIT}），
   *       minScore 是 0..1 之间的有限数值（后端 {@code MIN_MIN_SCORE}..{@code MAX_MIN_SCORE}）；</li>
   *   <li>rankingMode 只能是既有的两种模式：VECTOR_SIMILARITY / RERANK；</li>
   *   <li>重排字段必须配对出现：RERANK 必须带非空 rerankModel 且每条引用都有
   *       有限数字的重排分；VECTOR_SIMILARITY 不得带 rerankModel 或任何重排分；
   *       重排分是当前请求内的相对分，<b>不做范围校验</b>（保持既有语义）；</li>
   *   <li>引用按返回顺序对应连续的 K1… 与 rank=1…（页面不做本地重排，
   *       服务端顺序就是展示顺序，编号对不上说明响应不自洽）；
   *       条数不得超过生效 topK；相似度必须落在 [minScore, 1]
   *       （后端契约：分数有限、0..1 且不低于阈值）；</li>
   *   <li>citations 的每个元素都必须是 JSON 对象 —— null、数组、字符串、数字
   *       一律按「结果不完整」处理，绝不抛页面异常（null 元素读属性会抛
   *       TypeError，把状态留在「正在检索」—— R2 显式防住）。</li>
   * </ul>
   *
   * <p>任何错配都按「结果不完整」处理：不渲染为成功，也不猜测修正（例如按 rank
   * 重新编号或把字符串分数强行转数字）—— 检索可能已产生费用，但结果未取得。</p>
   */
  function searchResponseProblem(body) {
    if (!isNonEmptyString(body.provider) || !isNonEmptyString(body.model)) {
      return 'provider / model 缺失或不是非空字符串';
    }
    if (!isIntegerAtLeast(body.dimensions, 1)) {
      return 'dimensions 不是正整数';
    }
    if (!isIntegerAtLeast(body.topK, 1) || body.topK > 20) {
      return 'topK 不是 1..20 之间的正整数';
    }
    if (!isFiniteNumber(body.minScore) || body.minScore < 0 || body.minScore > 1) {
      return 'minScore 不是 0..1 之间的有限数值';
    }
    if (body.rankingMode !== 'VECTOR_SIMILARITY' && body.rankingMode !== 'RERANK') {
      return 'rankingMode 不是既有的两种模式之一（VECTOR_SIMILARITY / RERANK）';
    }
    if (!Array.isArray(body.citations)) {
      return 'citations 不是数组';
    }
    if (body.citations.length > body.topK) {
      return '引用条数 ' + body.citations.length + ' 超过生效 topK ' + body.topK;
    }
    if (body.rankingMode === 'RERANK') {
      if (!isNonEmptyString(body.rerankModel)) {
        return 'rankingMode 为 RERANK 但缺少非空字符串的重排模型 rerankModel';
      }
    } else if (body.rerankModel !== undefined) {
      return 'rankingMode 为 VECTOR_SIMILARITY 但出现了重排模型 rerankModel';
    }
    for (var i = 0; i < body.citations.length; i++) {
      var citation = body.citations[i];
      var at = '第 ' + (i + 1) + ' 条引用';
      if (citation === null || typeof citation !== 'object' || Array.isArray(citation)) {
        return at + '不是 JSON 对象（可能是 null、数组或其他原始值）';
      }
      if (!isNonEmptyString(citation.citationId) || citation.citationId !== 'K' + (i + 1)) {
        return at + '的编号不是按返回顺序连续的 K' + (i + 1);
      }
      if (!isIntegerAtLeast(citation.rank, 1) || citation.rank !== i + 1) {
        return at + '的 rank 不是连续的 ' + (i + 1);
      }
      if (!isNonEmptyString(citation.documentId)) {
        return at + '的 documentId 缺失或不是非空字符串';
      }
      if (!isIntegerAtLeast(citation.documentVersion, 0)) {
        return at + '的 documentVersion 不是非负整数';
      }
      if (!isNonEmptyString(citation.documentTitle)) {
        return at + '的 documentTitle 缺失或不是非空字符串';
      }
      if (!isIntegerAtLeast(citation.chunkIndex, 0)) {
        return at + '的 chunkIndex 不是非负整数';
      }
      if (!isNonEmptyString(citation.content)) {
        return at + '的 content 缺失或不是非空字符串';
      }
      if (!isFiniteNumber(citation.score)) {
        return at + '的相似度 score 不是有限数字';
      }
      if (citation.score < body.minScore || citation.score > 1) {
        return at + '的相似度 score 不在 [minScore, 1] 范围内';
      }
      if (body.rankingMode === 'RERANK' && !isFiniteNumber(citation.rerankScore)) {
        return at + '在 RERANK 模式下缺少有限数字的重排分 rerankScore';
      }
      if (body.rankingMode !== 'RERANK' && citation.rerankScore !== undefined) {
        return at + '在非重排模式下出现了重排分 rerankScore';
      }
    }
    return null;
  }

  function handleSearchResult(result) {
    // 拿到了响应头但读不到 body：请求已发出且可能已产生费用，结果未取得。
    if (result.bodyReadFailed) {
      setSearchState('failed', '结果未取得');
      setSearchError(
        '检索请求已发出，但读取响应体失败，无法取得检索结果。服务端可能已经调用了 Query Embedding' +
          '（可能产生费用），结果没有送达页面。页面不会自动重试。'
      );
      return;
    }

    if (result.transportError) {
      setSearchState('failed', '结果未取得');
      setSearchError(
        '检索请求网络中断，无法取得检索结果。请求可能已到达服务端并产生 Query Embedding 费用。' +
          '请先确认本地服务状态，再决定是否重新检索；页面不会自动重试。'
      );
      return;
    }

    // 400：服务端在校验阶段（调用向量化服务之前）就拒绝了请求 —— 确定结果，检索没有执行。
    if (result.status === 400) {
      setSearchState('failed', '检索被拒绝');
      setSearchError('检索被拒绝：' + describeProblem(result.status, result.body) +
        '。服务端在调用向量化服务之前就拒绝了请求，检索没有执行。');
      return;
    }

    // 503 只有在 problem.code 明确为 KNOWLEDGE_EMBEDDING_DISABLED（Basic 模式）时才是确定结果：
    // 未启用向量化，检索没有执行、没有调用向量化服务、未产生费用。其余 503（非 JSON / 缺 code /
    // code 不同）不在这份契约范围内，按「可能已产生费用但结果未取得」处理（落入下方 >= 500 分支）。
    if (result.status === 503 && problemCode(result.body) === 'KNOWLEDGE_EMBEDDING_DISABLED') {
      setSearchState('failed', '检索未执行');
      setSearchError(
        '检索未执行（HTTP 503）：Embedding 服务未启用（Basic 模式），没有调用向量化服务，' +
          '未产生费用，知识库也没有被修改。'
      );
      return;
    }

    // 502：向量服务或重排服务暂时不可用（problem 的 title/detail 会区分是哪一个）。
    // 请求已发出，Embedding（或重排）可能已被调用 —— 费用可能已产生，结果未取得。
    if (result.status === 502) {
      setSearchState('failed', '结果未取得');
      setSearchError('检索失败：' + describeProblem(result.status, result.body) +
        '。本次检索可能已产生 Query Embedding 或重排费用，但结果未取得。页面不会自动重试。');
      return;
    }

    if (result.status >= 500) {
      setSearchState('failed', '结果未取得');
      setSearchError('检索失败：' + describeProblem(result.status, result.body) +
        '。本次检索可能已产生费用，但结果未取得。页面不会自动重试。');
      return;
    }

    if (result.status !== 200) {
      setSearchState('failed', '检索失败');
      setSearchError('检索失败：' + describeProblem(result.status, result.body));
      return;
    }

    // 200 也必须自洽才展示：结构或必要字段缺失时按「结果不完整」处理。
    if (result.formatProblem !== null || result.body === null) {
      setSearchState('failed', '结果未取得');
      setSearchError('检索返回 200，但响应体无法解析（' + result.formatProblem + '）。' +
        '结果不完整，无法安全展示；本次检索可能已产生费用，结果未取得。页面不会自动重试。');
      return;
    }
    var problem = searchResponseProblem(result.body);
    if (problem !== null) {
      setSearchState('failed', '结果未取得');
      setSearchError('检索返回 200，但响应' + problem + '。' +
        '结果不完整，无法安全展示；本次检索可能已产生费用，结果未取得。页面不会自动重试。');
      return;
    }

    setSearchError('');
    setSearchState('done', '检索完成。');
    renderSearchResult(result.body);
  }

  // ---- 提交 -------------------------------------------------------------------

  /**
   * 读取可选数字输入。留空返回 undefined（请求体<b>不下发该字段</b>，交给服务端默认值）；
   * 填了但不是有限数字返回 null（调用方提示且<b>不消耗确认、不发请求</b>）。
   */
  function readOptionalNumber(id) {
    var raw = element(id);
    if (!raw) {
      return undefined;
    }
    var text = String(raw.value === undefined || raw.value === null ? '' : raw.value)
      .replace(/^\s+|\s+$/g, '');
    if (text.length === 0) {
      return undefined;
    }
    var value = Number(text);
    if (!isFiniteNumber(value)) {
      return null;
    }
    return value;
  }

  function submitSearch(event) {
    event.preventDefault();
    if (searching) {
      return;
    }

    var queryNode = element('knowledge-search-query');
    var query = queryNode ? String(queryNode.value).replace(/^\s+|\s+$/g, '') : '';
    if (query.length === 0) {
      // 前端输入校验：不发请求，也就不消耗本次费用确认（服务端始终是最终校验者）。
      setSearchError('请输入要检索的问题；本次未发送检索请求，费用确认未被消耗。');
      return;
    }

    var topK = readOptionalNumber('knowledge-search-topk');
    var minScore = readOptionalNumber('knowledge-search-minscore');
    if (topK === null || minScore === null) {
      setSearchError('topK 与 minScore 必须是数字或留空；本次未发送检索请求，费用确认未被消耗。');
      return;
    }

    if (!isSearchConfirmed()) {
      // 按钮在未确认时本应禁用；这里再拦一次，保证不存在「没确认就发请求」的路径。
      setSearchError('检索尚未确认：请先勾选「检索可能调用 Query Embedding 并产生费用」。');
      return;
    }

    // 费用确认是一次性授权：提交即消耗，勾选框同步清空。
    // 这次已发出的请求不读确认状态，在途期间勾选框怎么变都不影响它；
    // 之后想再检索（包括这次失败后重试）都必须重新勾选。
    setSearchConfirmation(false);

    // 请求只走 JSON body：问题不写入 URL，也不进入任何浏览器持久存储。
    var body = { query: query };
    if (topK !== undefined) {
      body.topK = topK;
    }
    if (minScore !== undefined) {
      body.minScore = minScore;
    }

    clearSearchResult();
    setSearchError('');
    setSearchBusy(true);
    setSearchState('pending', '正在检索…检索会调用向量化服务，可能产生 Query Embedding 费用。');

    guard(requestJson(SEARCH_PATH, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(body)
    }), function () {
      setSearchBusy(false);
    }, function () {
      setSearchState('failed', '结果未取得');
      setSearchError('检索请求在页面内异常终止，无法取得检索结果。' +
        '请求可能已到达服务端并产生 Query Embedding 费用。页面不会自动重试。');
    }).then(function (result) {
      if (result === null) {
        return; // 已由 onCrash 收尾
      }
      handleSearchResult(result);
    });
  }

  // ---- 事件绑定（全部为提交/变更触发，加载期不发起任何请求） --------------------

  function bindSearch() {
    var form = element('knowledge-search-form');
    if (form) {
      form.addEventListener('submit', submitSearch);
    }
    var confirmBox = element('knowledge-search-confirm');
    if (confirmBox) {
      confirmBox.addEventListener('change', function () {
        // 勾选/取消先落到授权标志上（一次性授权以它为准），再重估按钮与提示。
        setSearchConfirmation(confirmBox.checked);
        refreshSearchButton();
      });
    }
    refreshSearchButton();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindSearch);
  } else {
    bindSearch();
  }
})();
