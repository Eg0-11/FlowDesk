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
 * <li><b>200 也必须自洽才展示</b>：校验响应结构与必要字段，缺字段或结构不对时不渲染、
 *     提示结果不完整；200 + 空 citations 是正常的「无命中」，不是失败。</li>
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

  function missingField(body, field) {
    return body === null || typeof body !== 'object' || body[field] === undefined || body[field] === null;
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
   * 200 也必须自洽：顶层必要字段、citations 数组、每条引用的必要字段。
   * 任何缺失都不渲染，并如实说明「结果不完整」（检索可能已产生费用，结果未取得）。
   */
  function searchResponseProblem(body) {
    if (missingField(body, 'provider') || missingField(body, 'model') || missingField(body, 'dimensions') ||
        missingField(body, 'topK') || missingField(body, 'minScore') || missingField(body, 'rankingMode') ||
        missingField(body, 'citations')) {
      return '缺少 provider / model / dimensions / topK / minScore / rankingMode / citations 中的必要字段';
    }
    if (!Array.isArray(body.citations)) {
      return 'citations 不是数组';
    }
    for (var i = 0; i < body.citations.length; i++) {
      var citation = body.citations[i];
      if (missingField(citation, 'citationId') || missingField(citation, 'rank') ||
          missingField(citation, 'documentId') || missingField(citation, 'documentVersion') ||
          missingField(citation, 'documentTitle') || missingField(citation, 'chunkIndex') ||
          missingField(citation, 'content') || missingField(citation, 'score')) {
        return '第 ' + (i + 1) + ' 条引用缺少 citationId / rank / documentId / documentVersion / ' +
          'documentTitle / chunkIndex / content / score 中的必要字段';
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
