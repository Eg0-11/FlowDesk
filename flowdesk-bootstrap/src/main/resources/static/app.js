/*
 * FlowDesk 本机演示入口的脚本（FD-0023-A / B / B-R1 / C / C-R1 / C-R2）。
 *
 * 约束：
 *   - 所有请求都用**相对路径**（同源），不写死主机名与端口；
 *   - 页面加载时只请求只读的健康端点，不发写请求，也不请求文档向量化、知识检索或模型调用类接口
 *     （本文件里也不写出它们的路径），因此加载页面不产生任何模型费用；
 *   - 工单列表、详情、新建都在用户点击之后才发请求；页面里的写请求只有两处：新建工单与状态变更；
 *   - 用户输入与服务端数据一律只用 textContent 渲染：不把字符串当 HTML 插入，也不使用拼接 HTML 的写法；
 *   - 分类与优先级的取值与后端枚举一致：TicketCategory / TicketPriority（不修改 API 契约）；
 *   - 成功响应必须是「合法 JSON + 页面必需字段齐全」才算成功：空响应、畸形 JSON、缺字段一律报
 *     「响应格式异常」，绝不把 NaN / undefined 渲染出来，也绝不误报创建成功；
 *   - 列表加载期间禁止重复导航（按钮禁用 + 在飞标记）；列表、详情与状态变更都用自增令牌丢弃**过时响应**，
 *     保证回来的旧响应不能覆盖最新页面；
 *   - **状态变更的目标隔离与版本漂移保护**（FD-0023-C-R1）：切换详情时立刻清空旧工单的可操作状态并隐藏
 *     操作区；新详情失败（404 / 网络 / 格式异常）后绝不对旧工单发写请求；写请求前的预检必须校验响应体、
 *     工单 ID、规范强 ETag 与 version，只要强 ETag 与「用户当前看到的详情」不同就取消这次写入
 *     （**哪怕状态相同**），且**一个写请求都不发**；预检失败明确说明「写请求未发送」；A 的写请求在途时
 *     用户切到 B，A 的迟到响应不得覆盖 B 的详情；成功响应缺有效 ETag 或 version 与 ETag 不一致时
 *     不能宣称完整成功，只提示核查；
 *   - **异常结果后锁住状态变更操作区**（FD-0023-C-R2）：只要出现「预检发现 ETag 漂移 / 状态变化 /
 *     响应格式异常」或「写请求得到不完整的成功响应」，最新详情**仍可阅读**，但**旧操作区必须被清空且
 *     锁定** —— 按钮与输入框整体移除，并由 {@link actionsLocked} 让任何残留调用直接返回，
 *     使「连点两次旧操作」的 POST 计数恒为 0；**只有用户显式点「刷新详情」并拿到有效的单条详情响应
 *     （强 ETag 规范且与响应体 version 自洽）之后**，才按新状态重新渲染按钮。首次打开详情时若强 ETag
 *     缺失、格式不符合后端规范、或与响应体 version 不一致，同样只展示**只读详情**（不渲染操作区、
 *     不允许写入），并给出刷新入口。
 */
(function () {
    'use strict';

    /** 与后端 TicketCategory 一致（顺序即下拉框顺序）。 */
    var CATEGORIES = ['ACCOUNT_ACCESS', 'NETWORK', 'HARDWARE', 'SOFTWARE', 'OTHER'];

    /** 与后端 TicketPriority 一致（顺序即下拉框顺序）。 */
    var PRIORITIES = ['P1', 'P2', 'P3', 'P4'];

    /** 列表每页条数（只作为查询参数；翻页依据服务端返回的 page / hasNext / hasPrevious）。 */
    var PAGE_SIZE = 10;

    var lastPage = null;
    var lastHasPrev = null;
    var lastHasNext = null;
    var listLoading = false;
    var listToken = 0;
    var detailToken = 0;
    var creating = false;

    /** 最近一次成功渲染的详情与它的强 ETag（写请求的 If-Match 只允许来自单条详情的响应头）。 */
    var currentTicket = null;
    var currentETag = null;

    /** 状态变更进行中：禁用重复提交。 */
    var acting = false;

    /**
     * 状态变更的目标令牌（FD-0023-C-R1）。
     *
     * <p>每次切换详情或重新打开详情都会自增。写请求的两段（预检 GET、写 POST）都只认自己那一次的目标，
     * 因此「A 的写请求在途时用户打开 B」后，A 迟到的响应会被丢弃，**不会覆盖 B 的详情**，
     * 也不会把 B 的操作区改成 A 的状态。</p>
     */
    var actionToken = 0;

    /**
     * 操作区锁定标志（FD-0023-C-R2）。
     *
     * <p>出现「预检发现 ETag 漂移 / 状态变化 / 响应格式异常」或「写请求得到不完整的成功响应」时置为
     * true：详情照常可读，但**旧操作区被清空并锁定**。{@link performAction} / {@link requestAction}
     * 一看这个标志就直接返回，因此即使 DOM 上还有残留按钮被连点，也**一个 POST 都不会发**。
     * 只有用户显式刷新并拿到有效的单条详情响应（{@link openDetail} 成功路径）才会复位。</p>
     */
    var actionsLocked = false;

    function element(id) {
        return document.getElementById(id);
    }

    function setText(id, text) {
        var target = element(id);
        if (target) {
            target.textContent = text === undefined || text === null ? '' : String(text);
        }
    }

    function setClass(id, className) {
        var target = element(id);
        if (target) {
            target.className = className;
        }
    }

    function show(id, visible) {
        var target = element(id);
        if (target) {
            target.hidden = !visible;
        }
    }

    function setDisabled(id, disabled) {
        var target = element(id);
        if (target) {
            target.disabled = disabled;
        }
    }

    function describeFailure(result) {
        var body = result && result.body ? result.body : null;
        var parts = ['HTTP ' + (result ? result.status : '?')];
        if (body && body.code) {
            parts.push('code=' + body.code);
        }
        if (body && body.detail) {
            parts.push('detail=' + body.detail);
        }
        else if (body && body.title) {
            parts.push('detail=' + body.title);
        }
        return parts.join(' · ');
    }

    /**
     * 读一次 JSON 接口。
     *
     * @param url     相对路径
     * @param options fetch 选项（写请求才需要传）
     * @returns {Promise<{status:number, body:object|null, transportError:string|null, formatProblem:string|null}>}
     */
    function requestJson(url, options) {
        return fetch(url, options)
            .then(function (response) {
                var eTag = response.headers.get('ETag');
                return response.text().then(function (text) {
                    var trimmed = text === null || text === undefined ? '' : text.trim();
                    if (trimmed === '') {
                        return {
                            status: response.status,
                            body: null,
                            eTag: eTag,
                            transportError: null,
                            formatProblem: response.ok ? '服务端返回了空响应' : null
                        };
                    }
                    var parsed;
                    try {
                        parsed = JSON.parse(trimmed);
                    }
                    catch (error) {
                        return {
                            status: response.status,
                            body: null,
                            eTag: eTag,
                            transportError: null,
                            formatProblem: response.ok ? '服务端返回的不是合法 JSON' : null
                        };
                    }
                    if (parsed === null || typeof parsed !== 'object') {
                        return {
                            status: response.status,
                            body: null,
                            eTag: eTag,
                            transportError: null,
                            formatProblem: response.ok ? '服务端返回的 JSON 不是对象' : null
                        };
                    }
                    return { status: response.status, body: parsed, eTag: eTag, transportError: null, formatProblem: null };
                });
            })
            .catch(function (error) {
                return { status: 0, body: null, eTag: null, transportError: error.message, formatProblem: null };
            });
    }

    function isFiniteNumber(value) {
        return typeof value === 'number' && isFinite(value);
    }

    /**
     * 校验成功响应里页面必需字段是否存在且类型正确。
     *
     * @param body     解析后的响应体
     * @param required 字段名数组
     * @returns {string|null} 有问题时返回「缺少字段 xxx」这类说明，否则返回 null
     */
    function missingField(body, required) {
        for (var index = 0; index < required.length; index++) {
            var name = required[index];
            if (body[name] === undefined || body[name] === null) {
                return '缺少字段 ' + name;
            }
        }
        return null;
    }

    // ---------- 服务状态（页面加载时唯一会发出的请求） ----------

    function loadHealth() {
        requestJson('/actuator/health', { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                if (result.transportError) {
                    setText('health-main', '请求失败：' + result.transportError);
                    setClass('health-main', 'failed');
                    return;
                }
                if (result.body && result.body.status) {
                    setText('health-main', 'HTTP ' + result.status + ' · ' + result.body.status);
                    setClass('health-main', result.status === 200 && result.body.status === 'UP' ? 'ok' : 'warn');
                    return;
                }
                setText('health-main', '响应格式异常：没有 status 字段');
                setClass('health-main', 'failed');
            });
    }

    // ---------- 工单列表（点击后；只读；加载期间禁止重复导航，过时响应丢弃） ----------

    function setListBusy(busy) {
        listLoading = busy;
        setDisabled('load-tickets', busy);
        setDisabled('prev-page', busy);
        setDisabled('next-page', busy);
    }

    /**
     * 按「最后一次有效分页结果」恢复加载/上一页/下一页：
     * 加载失败时上一页/下一页必须与最后一次有效结果一致；**没有有效结果时两者都禁用**。
     */
    function applyNavigation() {
        setDisabled('load-tickets', false);
        setDisabled('prev-page', lastHasPrev !== true);
        setDisabled('next-page', lastHasNext !== true);
    }

    function renderPageMeta(body) {
        if (body.totalPages === 0) {
            // 后端空列表契约：page=0、totalPages=0、totalElements=0、hasNext=hasPrevious=false
            setText('tickets-meta', '共 0 条');
        }
        else {
            setText('tickets-meta', '第 ' + (body.page + 1) + ' 页 / 共 ' + body.totalPages + ' 页，共 '
                + body.totalElements + ' 条（本页 ' + body.items.length + ' 条）');
        }
        lastPage = body.page;
        lastHasPrev = body.hasPrevious;
        lastHasNext = body.hasNext;
        applyNavigation();
    }

    function renderTicketRows(items) {
        var table = element('tickets-table');
        var rows = table ? table.querySelector('tbody') : null;
        if (!rows) {
            return;
        }
        rows.replaceChildren();
        items.forEach(function (item) {
            var row = document.createElement('tr');
            [item.id, item.title, item.status, item.priority, item.requesterId].forEach(function (value) {
                var cell = document.createElement('td');
                cell.textContent = value === undefined || value === null ? '' : String(value);
                row.appendChild(cell);
            });

            var actions = document.createElement('td');
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = '详情';
            button.setAttribute('data-ticket-detail', item.id);
            button.addEventListener('click', function () {
                openDetail(item.id);
            });
            actions.appendChild(button);
            row.appendChild(actions);
            rows.appendChild(row);
        });
    }

    function listPageProblem(body) {
        var missing = missingField(body, ['items', 'page', 'size', 'totalPages', 'totalElements',
            'hasNext', 'hasPrevious']);
        if (missing) {
            return missing;
        }
        if (!Array.isArray(body.items)) {
            return 'items 不是数组';
        }
        if (!isFiniteNumber(body.page) || !isFiniteNumber(body.totalPages)
                || !isFiniteNumber(body.totalElements) || !isFiniteNumber(body.size)) {
            return '分页字段不是有效数字';
        }
        if (typeof body.hasNext !== 'boolean' || typeof body.hasPrevious !== 'boolean') {
            return 'hasNext / hasPrevious 不是布尔值';
        }
        for (var index = 0; index < body.items.length; index++) {
            var item = body.items[index];
            if (!item || typeof item !== 'object') {
                return 'items 里有非对象元素';
            }
            var itemMissing = missingField(item, ['id', 'title', 'status', 'priority', 'requesterId']);
            if (itemMissing) {
                return 'items[' + index + '] ' + itemMissing;
            }
        }
        return null;
    }

    function loadTickets(page) {
        var target = typeof page === 'number' ? page : 0;
        if (listLoading) {
            return;
        }
        var token = listToken + 1;
        listToken = token;

        setListBusy(true);
        show('tickets-error', false);
        show('tickets-table', false);
        show('tickets-state', true);
        setText('tickets-state', '加载中…');
        setText('tickets-meta', '');

        requestJson('/api/v1/tickets?page=' + target + '&size=' + PAGE_SIZE,
            { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                if (token !== listToken) {
                    // 过时响应：已经有更新的列表请求在跑或已完成，直接丢弃，不覆盖最新页面
                    return;
                }
                setListBusy(false);
                // 无论成功失败，先把上一页/下一页恢复成「最后一次有效结果」的样子
                applyNavigation();
                show('tickets-state', false);

                if (result.transportError) {
                    show('tickets-error', true);
                    setText('tickets-error', '请求失败（服务不可达）：' + result.transportError);
                    return;
                }
                if (result.status === 400 || result.status === 404) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：' + describeFailure(result));
                    return;
                }
                if (result.status !== 200) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：' + describeFailure(result));
                    return;
                }
                if (result.formatProblem) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：响应格式异常（' + result.formatProblem + '）');
                    return;
                }
                var problem = listPageProblem(result.body);
                if (problem) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：响应格式异常（' + problem + '）');
                    return;
                }

                renderPageMeta(result.body);
                if (result.body.items.length === 0) {
                    show('tickets-state', true);
                    setText('tickets-state', result.body.totalElements === 0
                        ? '还没有任何工单（空列表）'
                        : '本页没有工单');
                    return;
                }
                renderTicketRows(result.body.items);
                show('tickets-table', true);
            });
    }

    // ---------- 工单详情（点击后；只读；后点的那条胜出，旧响应不能覆盖） ----------

    function detailProblem(body) {
        var missing = missingField(body, ['id', 'title', 'description', 'category', 'priority',
            'requesterId', 'status', 'version']);
        if (missing) {
            return missing;
        }
        if (!isFiniteNumber(body.version)) {
            return 'version 不是有效数字';
        }
        return null;
    }

    /**
     * 清空「可操作工单」的全部状态并立刻隐藏操作区（FD-0023-C-R1）。
     *
     * <p>切换详情的第一件事就是调用它：旧工单的 id / ETag 与 DOM 上的按钮、输入框都在同一时刻消失，
     * 因此**不存在**「界面已经换到新工单、内部还指向旧工单」的窗口。新详情如果最终失败
     * （404 / 网络 / 格式异常），这个清空状态会一直保持，写请求也就永远没有目标可发。</p>
     *
     * <p>FD-0023-C-R2：清空的同时**默认上锁** —— 切换目标本身就是「旧操作区不再有效」，
     * 只有随后的有效详情响应才会解锁。</p>
     */
    function clearCurrentTarget() {
        currentTicket = null;
        currentETag = null;
        actionsLocked = true;
        var buttons = element('detail-action-buttons');
        if (buttons) {
            buttons.replaceChildren();
        }
        var fields = element('detail-action-fields');
        if (fields) {
            fields.replaceChildren();
        }
        var confirmBox = element('detail-action-confirm');
        if (confirmBox) {
            confirmBox.replaceChildren();
        }
        show('detail-action-error', false);
        show('detail-action-result', false);
        show('detail-actions', false);
    }

    /**
     * 异常结果后锁住操作区（FD-0023-C-R2）：**保留最新详情供阅读**，但把旧操作区清空并上锁。
     *
     * <p>与 {@link clearCurrentTarget} 的区别：本函数**不动详情区**（调用方通常已经用服务端返回的
     * 最新数据填好了详情），也不把 currentTicket / currentETag 置空 —— 它们仍是「用户当前看到的详情」，
     * 用于让用户看得见最新版本；但 {@link actionsLocked} 为 true 会让任何写路径直接返回。</p>
     *
     * @param message 锁定的原因文案（会显示在操作区错误位）
     * @param ticketId 仍要保留的刷新目标（点「刷新详情」用它重新取数）
     */
    function lockActions(message, ticketId) {
        actionsLocked = true;
        currentETag = null;
        setActionsBusy(false);
        var buttons = element('detail-action-buttons');
        if (buttons) {
            buttons.replaceChildren();
        }
        var fields = element('detail-action-fields');
        if (fields) {
            fields.replaceChildren();
        }
        var confirmBox = element('detail-action-confirm');
        if (confirmBox) {
            confirmBox.replaceChildren();
        }
        // 操作区保持可见：用户需要看到「为什么不能操作」以及刷新入口。
        show('detail-action-result', false);
        show('detail-actions', true);
        showActionError(message);
        showRefreshHint(ticketId);
    }

    function openDetail(ticketId) {
        // 先作废在途的状态变更，再清空操作区：任何迟到的写响应都不能落到这条新详情上。
        actionToken = actionToken + 1;
        setActionsBusy(false);
        clearCurrentTarget();

        var token = detailToken + 1;
        detailToken = token;

        show('detail-error', false);
        show('detail-body', false);
        show('detail-state', true);
        setText('detail-state', '加载中…');

        requestJson('/api/v1/tickets/' + encodeURIComponent(ticketId), { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                if (token !== detailToken) {
                    // 过时响应：用户已经点了别的工单，丢弃它
                    return;
                }
                show('detail-state', false);

                if (result.transportError) {
                    show('detail-error', true);
                    setText('detail-error', '请求失败（服务不可达）：' + result.transportError
                        + ' 这条工单的操作区已清空，不会对上一条件工单发出写请求。');
                    return;
                }
                if (result.status === 404) {
                    show('detail-error', true);
                    setText('detail-error', '未找到该工单：' + describeFailure(result)
                        + ' 这条工单的操作区已清空，不会对上一条件工单发出写请求。');
                    return;
                }
                if (result.status !== 200) {
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：' + describeFailure(result)
                        + ' 这条工单的操作区已清空，不会对上一条件工单发出写请求。');
                    return;
                }
                if (result.formatProblem) {
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：响应格式异常（' + result.formatProblem
                        + '） 这条工单的操作区已清空，不会对上一条件工单发出写请求。');
                    return;
                }
                var problem = detailProblem(result.body);
                if (problem) {
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：响应格式异常（' + problem
                        + '） 这条工单的操作区已清空，不会对上一条件工单发出写请求。');
                    return;
                }
                if (result.body.id !== ticketId) {
                    // 请求 A 却拿到 B 的详情：按格式异常处理，绝不把 B 当成 A 的可操作目标
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：响应格式异常（返回的工单编号与请求不一致）'
                        + ' 这条工单的操作区已清空，不会对上一条件工单发出写请求。');
                    return;
                }
                currentTicket = result.body;
                currentETag = result.eTag;

                // FD-0023-C-R2：详情可读，但「能不能操作」取决于强 ETag 是否可用且自洽。
                // 强 ETag 缺失 / 不符合后端规范（非 "n" 形式、或带了 W/ 弱校验）/ 与响应体 version
                // 不一致时，只展示只读详情：不渲染操作区，也就没有按钮可点。
                if (!isStrongETag(result.eTag)) {
                    currentETag = null;
                    renderDetail(result.body, result.eTag, true);
                    lockActions('只读详情：这条工单的响应没有可用的强 ETag（收到：'
                        + (result.eTag === null || result.eTag === undefined ? '无 ETag 响应头' : result.eTag)
                        + '）。没有真实版本就无法安全地做状态变更，因此这里不提供操作，仅展示详情。',
                        result.body.id);
                    return;
                }
                if (result.eTag !== '"' + result.body.version + '"') {
                    currentETag = null;
                    renderDetail(result.body, result.eTag, true);
                    lockActions('只读详情：这条工单的强 ETag（' + result.eTag + '）与响应体版本（'
                        + result.body.version + '）不一致，无法确认版本。这里不提供操作，仅展示详情。',
                        result.body.id);
                    return;
                }
                actionsLocked = false;
                renderDetail(result.body, result.eTag);
            });
    }

    function appendRow(box, label, value) {
        var row = document.createElement('div');
        row.className = 'detail-row';
        var name = document.createElement('span');
        name.className = 'field-name';
        name.textContent = label;
        var text = document.createElement('span');
        text.className = 'field-value';
        text.textContent = value === undefined || value === null || value === '' ? '—' : String(value);
        row.appendChild(name);
        row.appendChild(text);
        box.appendChild(row);
    }

    /**
     * 渲染详情（15 行字段 + ETag 行，全部用 textContent）。
     *
     * @param ticket              详情响应体（已通过字段校验）
     * @param eTag                该响应的强 ETag（可能为 null）
     * @param preserveActionPanel true 表示这是「预检回填」，只刷新字段、不动操作区（FD-0023-C-R1）
     */
    function renderDetail(ticket, eTag, preserveActionPanel) {
        var box = element('detail-body');
        if (!box) {
            return;
        }
        box.replaceChildren();
        appendRow(box, '编号', ticket.id);
        appendRow(box, '标题', ticket.title);
        appendRow(box, '描述', ticket.description);
        appendRow(box, '分类', ticket.category);
        appendRow(box, '优先级', ticket.priority);
        appendRow(box, '提交人', ticket.requesterId);
        appendRow(box, '处理人', ticket.assigneeId);
        appendRow(box, '状态', ticket.status);
        appendRow(box, '解决说明', ticket.resolution);
        appendRow(box, '创建时间', ticket.createdAt);
        appendRow(box, '更新时间', ticket.updatedAt);
        appendRow(box, '解决时间', ticket.resolvedAt);
        appendRow(box, '关闭时间', ticket.closedAt);
        appendRow(box, '版本', ticket.version);
        appendRow(box, 'ETag', isStrongETag(eTag) ? eTag : (eTag === null || eTag === undefined ? '（响应未带 ETag）' : eTag));
        show('detail-body', true);
        // 只有「作为当前可操作目标」渲染时才展示操作区。预检结果回填详情时（preserveActionPanel）
        // 不得重新打开操作区 —— 否则预检失败后按钮又出现，用户会对着一个已经不成立的目标再点一次。
        if (!preserveActionPanel) {
            renderActions(ticket);
        }
        var section = element('detail');
        if (section && section.scrollIntoView) {
            section.scrollIntoView({ block: 'start' });
        }
    }

    // ---------- 状态变更（沿用后端已有端点；版本只能来自单条详情的强 ETag） ----------

    var ACTION_LABELS = {
        assign: '分配', reassign: '重新分配', start: '开始处理', resolve: '解决', close: '关闭'
    };

    var ACTION_PATHS = {
        assign: 'assign', reassign: 'reassign', start: 'start', resolve: 'resolve', close: 'close'
    };

    /** 强 ETag 形如 {@code "4"}：必须用双引号包裹，且不能是 {@code W/} 弱校验。 */
    function isStrongETag(value) {
        return typeof value === 'string' && /^"[^"]*"$/.test(value);
    }

    /**
     * 按工单的**真实状态**给出可用操作：
     * NEW → 分配；ASSIGNED → 重新分配 / 开始；IN_PROGRESS → 重新分配 / 解决；RESOLVED → 关闭；CLOSED → 无操作。
     *
     * @param status 工单状态
     * @returns {string[]} 可用操作
     */
    function availableActions(status) {
        if (status === 'NEW') {
            return ['assign'];
        }
        if (status === 'ASSIGNED') {
            return ['reassign', 'start'];
        }
        if (status === 'IN_PROGRESS') {
            return ['reassign', 'resolve'];
        }
        if (status === 'RESOLVED') {
            return ['close'];
        }
        return [];
    }

    function setActionsBusy(busy) {
        acting = busy;
        var buttons = document.querySelectorAll('#detail-action-buttons button, #detail-action-confirm button');
        for (var index = 0; index < buttons.length; index++) {
            buttons[index].disabled = busy;
        }
    }

    function actionFailure(result) {
        var parts = [];
        if (result.body && result.body.code) {
            parts.push('code=' + result.body.code);
        }
        if (result.body && result.body.detail) {
            parts.push('detail=' + result.body.detail);
        }
        return parts.length === 0 ? '响应没有 code/detail' : parts.join(' · ');
    }

    function showActionError(message) {
        show('detail-action-error', true);
        setText('detail-action-error', message);
    }

    function showRefreshHint(ticketId) {
        var box = document.getElementById('detail-action-error');
        if (!box || !ticketId) {
            return;
        }
        var refresh = document.createElement('button');
        refresh.type = 'button';
        refresh.id = 'refresh-detail';
        refresh.textContent = '刷新详情';
        refresh.addEventListener('click', function () {
            openDetail(ticketId);
        });
        box.appendChild(document.createTextNode(' '));
        box.appendChild(refresh);
    }

    /**
     * 按工单的真实状态重建操作区（FD-0023-C-R2：这是**唯一**的解锁入口）。
     *
     * <p>只有「用户显式刷新并拿到有效的单条详情响应」（{@link openDetail} 的成功路径）才会走到这里，
     * 因此调用它就意味着解锁；这里显式复位 {@link actionsLocked}，让解锁状态与可见的按钮始终一致。</p>
     */
    function renderActions(ticket) {
        var buttons = element('detail-action-buttons');
        var fields = element('detail-action-fields');
        if (!buttons || !fields) {
            return;
        }
        actionsLocked = false;
        show('detail-action-error', false);
        show('detail-action-result', false);
        show('detail-action-confirm', false);
        buttons.replaceChildren();
        fields.replaceChildren();
        show('detail-actions', true);

        var actions = availableActions(ticket.status);
        if (actions.length === 0) {
            var note = document.createElement('p');
            note.className = 'hint';
            note.textContent = '当前状态（' + ticket.status + '）没有可用的状态变更操作。';
            fields.appendChild(note);
            return;
        }

        if (actions.indexOf('assign') >= 0 || actions.indexOf('reassign') >= 0) {
            var assigneeRow = document.createElement('div');
            assigneeRow.className = 'field';
            var assigneeLabel = document.createElement('label');
            assigneeLabel.setAttribute('for', 'action-assignee');
            assigneeLabel.textContent = '处理人';
            var assigneeInput = document.createElement('input');
            assigneeInput.id = 'action-assignee';
            assigneeInput.type = 'text';
            assigneeInput.maxLength = 64;
            assigneeInput.value = ticket.assigneeId === null || ticket.assigneeId === undefined
                ? '' : String(ticket.assigneeId);
            assigneeRow.appendChild(assigneeLabel);
            assigneeRow.appendChild(assigneeInput);
            fields.appendChild(assigneeRow);
        }
        if (actions.indexOf('resolve') >= 0) {
            var resolutionRow = document.createElement('div');
            resolutionRow.className = 'field';
            var resolutionLabel = document.createElement('label');
            resolutionLabel.setAttribute('for', 'action-resolution');
            resolutionLabel.textContent = '解决说明';
            var resolutionInput = document.createElement('textarea');
            resolutionInput.id = 'action-resolution';
            resolutionInput.rows = 2;
            resolutionInput.maxLength = 2000;
            resolutionRow.appendChild(resolutionLabel);
            resolutionRow.appendChild(resolutionInput);
            fields.appendChild(resolutionRow);
        }

        actions.forEach(function (action) {
            var button = document.createElement('button');
            button.type = 'button';
            button.textContent = ACTION_LABELS[action];
            button.setAttribute('data-action', action);
            button.addEventListener('click', function () {
                requestAction(action);
            });
            buttons.appendChild(button);
        });
    }

    /**
     * 用户点了某条操作（FD-0023-C-R1）：
     * <ul>
     *   <li>关闭（不可逆）：先问一次「是否确认关闭」，确认后才进入预检 + 写请求；</li>
     *   <li>其余操作：直接进入预检 + 写请求。</li>
     * </ul>
     */
    function requestAction(action) {
        if (acting) {
            return;
        }
        // FD-0023-C-R2：操作区被锁定后，任何入口（含残留按钮被连点）都不再进入写路径。
        if (actionsLocked || !currentTicket || !isStrongETag(currentETag)) {
            return;
        }
        if (action !== 'close') {
            performAction(action);
            return;
        }
        var ticketId = currentTicket ? currentTicket.id : null;
        if (ticketId === null || !isStrongETag(currentETag)) {
            return;
        }
        var token = actionToken;
        var confirmBox = element('detail-action-confirm');
        if (!confirmBox) {
            return;
        }
        confirmBox.replaceChildren();
        var text = document.createElement('span');
        text.className = 'field-value';
        text.textContent = '关闭后不能再做状态变更，确认关闭这条工单？';
        confirmBox.appendChild(text);
        var yes = document.createElement('button');
        yes.type = 'button';
        yes.id = 'confirm-close';
        yes.textContent = '确认关闭';
        yes.addEventListener('click', function () {
            show('detail-action-confirm', false);
            if (token !== actionToken) {
                return;
            }
            performAction('close');
        });
        confirmBox.appendChild(yes);
        var no = document.createElement('button');
        no.type = 'button';
        no.id = 'cancel-close';
        no.textContent = '取消';
        no.addEventListener('click', function () {
            show('detail-action-confirm', false);
        });
        confirmBox.appendChild(no);
        show('detail-action-confirm', true);
    }

    /**
     * 关闭工单的**第二次**确认：预检通过后再问一次，避免「确认期间详情已经切走」时误关。
     *
     * @param ticketId    目标工单
     * @param requestETag 预检得到的强 ETag（本次写请求将要使用的 If-Match）
     */
    function requestCloseConfirmation(ticketId, requestETag) {
        var token = actionToken;
        setActionsBusy(false);
        var confirmBox = element('detail-action-confirm');
        if (!confirmBox) {
            return;
        }
        confirmBox.replaceChildren();
        var text = document.createElement('span');
        text.className = 'field-value';
        text.textContent = '已核对最新版本（' + requestETag
            + '）。关闭后不能再做状态变更，确认现在关闭这条工单？';
        confirmBox.appendChild(text);
        var yes = document.createElement('button');
        yes.type = 'button';
        yes.id = 'confirm-close-final';
        yes.textContent = '确认关闭';
        yes.addEventListener('click', function () {
            show('detail-action-confirm', false);
            if (token !== actionToken) {
                // 确认期间用户已经切到别的详情：绝不再发这个写请求
                return;
            }
            sendActionRequest('close', ticketId, null, requestETag, null);
        });
        confirmBox.appendChild(yes);
        var no = document.createElement('button');
        no.type = 'button';
        no.id = 'cancel-close-final';
        no.textContent = '取消';
        no.addEventListener('click', function () {
            show('detail-action-confirm', false);
            if (token !== actionToken) {
                return;
            }
            showActionError('已取消关闭：写请求未发送，工单状态未改变。');
        });
        confirmBox.appendChild(no);
        show('detail-action-confirm', true);
    }

    /**
     * 发写请求（预检已经通过），并把结果交给 {@link handleActionResult}。
     *
     * @param action          操作名
     * @param ticketId        目标工单（也是预检与写请求必须一致的编号）
     * @param displayedStatus 发起操作时用户看到的状态（仅用于成功文案之外的判断）
     * @param requestETag     预检得到的强 ETag，原样放进 If-Match
     * @param payload         请求体（可为 null）
     */
    function sendActionRequest(action, ticketId, displayedStatus, requestETag, payload) {
        var token = actionToken;
        setActionsBusy(true);
        show('detail-action-error', false);
        show('detail-action-result', false);
        show('detail-state', true);
        setText('detail-state', action === 'close' ? '正在关闭…' : '正在提交状态变更…');

        var headers = { 'Accept': 'application/json', 'If-Match': requestETag };
        var options = { method: 'POST', headers: headers };
        if (payload) {
            headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(payload);
        }
        requestJson('/api/v1/tickets/' + encodeURIComponent(ticketId)
            + '/' + ACTION_PATHS[action], options)
            .then(function (result) {
                if (token !== actionToken) {
                    // A 的写响应迟到了，而用户已经切到 B：结果只对 A 有意义，
                    // 绝不能拿它去覆盖 B 的详情或操作区。
                    return;
                }
                show('detail-state', false);
                handleActionResult(action, ticketId, displayedStatus, requestETag, result);
            });
    }

    /**
     * 状态变更的两段式提交（FD-0023-C-R1 收紧版）。
     *
     * <p>第一段：单条 {@code GET /api/v1/tickets/{id}}，**只**从它的响应头取强 ETag。
     * 第二段：带 {@code If-Match} 的写请求。</p>
     *
     * <p>第一段同时充当「目标仍然成立」的证明，逐条校验：</p>
     * <ul>
     *   <li>响应体与工单 ID 一致（请求谁就必须拿到谁）；</li>
     *   <li>强 ETag 存在且规范（{@code "n"} 形式，不是弱校验）；</li>
     *   <li>{@code version} 必须是有效数字；</li>
     *   <li>ETag 与 version 必须自洽（{@code ETag == "\"version\""}）；</li>
     *   <li><b>强 ETag 必须与「用户当前看到的详情」完全相同</b> —— 只要不同，哪怕状态一样，
     *       也判定为版本漂移：取消本次写入、提示刷新，且**一个写请求都不发**。</li>
     * </ul>
     *
     * @param action 操作名（assign / reassign / start / resolve / close）
     */
    function performAction(action) {
        // FD-0023-C-R2：锁定后 write 路径的第一道闸门 —— 连点旧按钮也在这里被挡住，POST 恒为 0。
        if (actionsLocked || acting || !currentTicket || !isStrongETag(currentETag)) {
            return;
        }
        var ticketId = currentTicket.id;
        var displayedStatus = currentTicket.status;
        var displayedETag = currentETag;
        var payload = null;
        if (action === 'assign' || action === 'reassign') {
            var assignee = element('action-assignee');
            payload = { assigneeId: assignee ? assignee.value : '' };
        }
        else if (action === 'resolve') {
            var resolution = element('action-resolution');
            payload = { resolution: resolution ? resolution.value : '' };
        }

        var token = actionToken;
        setActionsBusy(true);
        show('detail-action-error', false);
        show('detail-action-result', false);
        show('detail-state', true);
        setText('detail-state', action === 'close' ? '正在关闭…' : '正在提交状态变更…');

        // 第一步：**只**从单条详情的响应头取强 ETag；写请求的 If-Match 原样使用它。
        // 列表接口不返回 ETag，版本也不在请求体里，绝不用列表里的 version 猜造请求头。
        requestJson('/api/v1/tickets/' + encodeURIComponent(ticketId), { headers: { 'Accept': 'application/json' } })
            .then(function (probe) {
                if (token !== actionToken) {
                    // 用户已经在这次预检的飞行途中打开了别的工单：这次操作整体作废，不写、不渲染。
                    return;
                }
                show('detail-state', false);

                // FD-0023-C-R2：预检一旦不成立（网络 / 非 200 / 格式异常 / 编号不符 / ETag 不可用），
                // 就不再相信任何既有目标 —— 操作区整体锁住，用户只能通过刷新重新取得可操作状态。
                if (probe.transportError) {
                    lockActions('写请求未发送：取最新详情的预检请求在网络层失败（'
                        + probe.transportError + '）。无法确认目标是否仍然成立，操作区已锁住；'
                        + '请刷新详情后重试。', ticketId);
                    return;
                }
                if (probe.status !== 200) {
                    lockActions('写请求未发送：取最新详情的预检返回 HTTP ' + probe.status + '（'
                        + actionFailure(probe) + '）。目标工单可能已不存在或不可读，操作区已锁住；'
                        + '请刷新详情。', ticketId);
                    return;
                }
                if (probe.formatProblem) {
                    lockActions('写请求未发送：取最新详情的预检响应格式异常（' + probe.formatProblem
                        + '）。无法确认目标是否仍然成立，操作区已锁住；请刷新详情。', ticketId);
                    return;
                }
                var probeProblem = detailProblem(probe.body);
                if (probeProblem) {
                    lockActions('写请求未发送：取最新详情的预检响应格式异常（' + probeProblem
                        + '）。无法确认目标是否仍然成立，操作区已锁住；请刷新详情。', ticketId);
                    return;
                }
                if (probe.body.id !== ticketId) {
                    lockActions('写请求未发送：预检返回的工单编号（' + probe.body.id
                        + '）与当前操作目标（' + ticketId + '）不一致。操作区已锁住，请刷新详情。',
                        ticketId);
                    return;
                }
                if (!isStrongETag(probe.eTag)) {
                    lockActions('写请求未发送：单条详情响应没有可用的强 ETag（收到：'
                        + (probe.eTag === null || probe.eTag === undefined ? '无 ETag 响应头' : probe.eTag)
                        + '）。写请求必须带真实版本，不能用猜造的请求头。操作区已锁住，请刷新详情。',
                        ticketId);
                    return;
                }
                if (probe.eTag !== '"' + probe.body.version + '"') {
                    lockActions('写请求未发送：预检的强 ETag（' + probe.eTag + '）与响应体版本（'
                        + probe.body.version + '）不一致。操作区已锁住，请刷新详情。', ticketId);
                    return;
                }
                var requestETag = probe.eTag;

                if (requestETag !== displayedETag) {
                    // 版本漂移：服务端的当前版本和用户看到的不一样了。即使状态相同也取消写入。
                    // FD-0023-C-R2：先把详情刷新成真实的最新详情（**保留可读**），然后**锁住操作区** ——
                    // 旧按钮被清空、actionsLocked=true，连点旧操作也发不出任何 POST；
                    // 用户必须先显式刷新并拿到有效的单条详情响应，才会按新状态重建按钮。
                    currentTicket = probe.body;
                    currentETag = requestETag;
                    renderDetail(probe.body, requestETag, true);
                    lockActions('写请求未发送：这条工单的版本已经变化'
                        + (probe.body.status === displayedStatus ? '（状态仍是 ' + displayedStatus + '）'
                            : '（状态也从 ' + displayedStatus + ' 变成了 ' + probe.body.status + '）')
                        + ' —— 你看到的版本是 ' + displayedETag + '，服务端当前是 ' + requestETag
                        + '。为避免覆盖别人的修改，本次操作已取消，操作区已锁住；'
                        + '请先刷新详情，确认最新状态后再重新操作。', ticketId);
                    return;
                }

                if (probe.body.status !== displayedStatus) {
                    // 正常情况下状态变化必然伴随版本变化（上面的分支已经拦住）；这里只作为
                    // 「版本没变但状态变了」这种异常响应的兜底。同样：详情可读、操作区锁住。
                    currentTicket = probe.body;
                    currentETag = requestETag;
                    renderDetail(probe.body, requestETag, true);
                    lockActions('写请求未发送：这条工单的状态已经变化（现在是 ' + probe.body.status
                        + '），本次操作已取消，操作区已锁住；请先刷新详情，再按新的可用操作重试。',
                        ticketId);
                    return;
                }

                if (action === 'close') {
                    // 关闭是不可逆的：预检已经确认目标成立，最后一次请用户确认。
                    // 确认期间若用户切走详情，actionToken 会变化，performCloseAction 会自己退出。
                    requestCloseConfirmation(ticketId, requestETag);
                    return;
                }

                sendActionRequest(action, ticketId, displayedStatus, requestETag, payload);
            });
    }

    /**
     * 处理写请求的响应（只有「目标仍然是当前详情」时才会走到这里）。
     *
     * @param action           操作名
     * @param ticketId         本次操作的工单
     * @param displayedStatus  发起操作时用户看到的状态
     * @param requestETag      本次实际使用的 If-Match
     * @param result           写请求结果
     */
    function handleActionResult(action, ticketId, displayedStatus, requestETag, result) {
        setActionsBusy(false);

        // FD-0023-C-R2：下面这些「结果不确定 / 不完整」的分支，历史版本只是把按钮重新启用，
        // 于是用户可以直接再点一次同一个操作。现在统一改为**锁住操作区**：
        // 详情仍可读（能读到的部分照旧），但按钮被清空、actionsLocked=true ——
        // 任何残留调用都被挡住（POST=0），只能通过显式刷新重新取得可操作状态。
        if (result.transportError) {
            lockActions('操作结果待确认（请求中断：' + result.transportError
                + '）。请先刷新详情确认最新状态，勿直接重试；操作区已锁住。', ticketId);
            return;
        }
        if (result.status === 412) {
            lockActions('版本冲突（HTTP 412 · ' + actionFailure(result)
                + '）：这条工单已被其他操作更新，本次操作没有生效。操作区已锁住，请刷新详情后重试。',
                ticketId);
            return;
        }
        if (result.status === 428) {
            lockActions('缺少前置条件（HTTP 428 · ' + actionFailure(result)
                + '）：服务端要求先取得最新版本才能变更状态。操作区已锁住，请刷新详情。', ticketId);
            return;
        }
        if (result.status === 409) {
            lockActions('状态冲突（HTTP 409 · ' + actionFailure(result)
                + '）：当前状态不允许这个操作。操作区已锁住，请刷新详情后按可用操作重试。', ticketId);
            return;
        }
        if (result.status === 400) {
            lockActions('请求被拒绝（HTTP 400 · ' + actionFailure(result)
                + '）。操作区已锁住，请刷新详情后重试。', ticketId);
            return;
        }
        if (result.status === 404) {
            lockActions('工单不存在（HTTP 404 · ' + actionFailure(result)
                + '）。操作区已锁住，请刷新详情。', ticketId);
            return;
        }
        if (result.status >= 400 && result.status < 500) {
            lockActions('操作被拒绝（HTTP ' + result.status + ' · ' + actionFailure(result)
                + '）。操作区已锁住，请刷新详情后重试。', ticketId);
            return;
        }
        if (result.status >= 500) {
            lockActions('操作结果待确认（服务端返回 HTTP ' + result.status
                + '，操作可能已经生效）。请先刷新详情确认最新状态，勿直接重试；操作区已锁住。', ticketId);
            return;
        }
        if (result.status !== 200) {
            lockActions('操作结果待确认（收到意外状态码 HTTP ' + result.status
                + '）。请先刷新详情确认最新状态，勿直接重试；操作区已锁住。', ticketId);
            return;
        }
        if (result.formatProblem) {
            lockActions('操作结果待确认（响应体异常：' + result.formatProblem
                + '）。请先刷新详情确认最新状态，勿直接重试；操作区已锁住。', ticketId);
            return;
        }
        var problem = detailProblem(result.body);
        if (problem) {
            lockActions('操作结果待确认（响应体异常：' + problem
                + '）。请先刷新详情确认最新状态，勿直接重试；操作区已锁住。', ticketId);
            return;
        }
        if (result.body.id !== ticketId) {
            // 200 但回来的不是这条工单：不能把它当成这条工单的成功结果
            lockActions('操作结果待确认（响应体异常：返回的工单编号与本次操作的目标不一致）'
                + '。请先刷新详情确认最新状态，勿直接重试；操作区已锁住。', ticketId);
            return;
        }
        if (result.body.version <= versionOfETag(requestETag)) {
            // 版本没有前进：服务端说 200，但版本没有递增，说明结果不能按「本次操作已生效」理解
            lockActions('操作结果待确认（响应体异常：版本没有按预期递增 —— 本次 If-Match 为 ' + requestETag
                + '，响应版本为 ' + result.body.version
                + '）。请先刷新详情确认最新状态，勿直接重试；操作区已锁住。', ticketId);
            return;
        }
        if (!isStrongETag(result.eTag) || result.eTag !== '"' + result.body.version + '"') {
            // 成功响应缺有效 ETag，或 ETag 与版本不自洽：不能宣称完整成功，只能提示核查。
            // FD-0023-C-R2：详情刷新为服务端返回的最新详情（版本已经前进，**可读**），
            // 但操作区必须**锁住** —— 否则用户会对着一个拿不到可靠版本的目标再提交一次。
            currentTicket = result.body;
            currentETag = null;
            renderDetail(result.body, result.eTag, true);
            lockActions('操作可能已生效（' + ACTION_LABELS[action] + '）：服务端返回 HTTP 200、状态 '
                + result.body.status + '、版本 ' + result.body.version
                + '，但' + (isStrongETag(result.eTag)
                    ? 'ETag（' + result.eTag + '）与版本（' + result.body.version + '）不一致'
                    : '响应缺少可用的强 ETag（收到：' + (result.eTag === null || result.eTag === undefined
                        ? '无 ETag 响应头' : result.eTag) + '）')
                + '。本次不按「完整成功」处理，操作区已锁住：请刷新详情核对最新状态后再继续操作。',
                ticketId);
            return;
        }

        currentTicket = result.body;
        currentETag = result.eTag;
        actionsLocked = false;
        renderDetail(result.body, result.eTag);
        show('detail-action-result', true);
        setText('detail-action-result', '操作成功：' + ACTION_LABELS[action]
            + ' → 状态 ' + result.body.status
            + '，版本 ' + result.body.version
            + '，新 ETag ' + result.eTag
            + '（本次请求使用的 If-Match 为 ' + requestETag + '）');
    }

    /**
     * 把强 ETag 解析成版本号，供「版本必须递增」这一判断使用。
     *
     * @param eTag 形如 {@code "12"} 的强 ETag
     * @returns {number} 解析出的版本号；不是规范强 ETag 时返回 -1
     */
    function versionOfETag(eTag) {
        if (!isStrongETag(eTag)) {
            return -1;
        }
        var parsed = Number(eTag.slice(1, -1));
        return isFinite(parsed) ? parsed : -1;
    }

    // ---------- 新建工单（唯一的写请求；防重复提交；响应不合法不算成功） ----------

    function fillSelect(id, values) {
        var select = element(id);
        if (!select) {
            return;
        }
        select.replaceChildren();
        values.forEach(function (value) {
            var option = document.createElement('option');
            option.value = value;
            option.textContent = value;
            select.appendChild(option);
        });
    }

    function setCreateBusy(busy) {
        creating = busy;
        setDisabled('create-submit', busy);
    }

    /**
     * 创建结果不确定（网络异常、5xx、201 但响应体异常）：不说是失败，也不说是成功，
     * 明确提示先去列表确认、不要直接重试；表单内容保留，便于对照。
     *
     * @param detail 具体原因（已在调用处描述清楚）
     */
    function renderCreateIndeterminate(detail) {
        show('create-error', true);
        setText('create-error', '创建结果待确认（' + detail + '）。请先查列表确认是否已创建，勿直接重试。');
        var box = element('create-result');
        if (box) {
            box.replaceChildren();
            var actions = document.createElement('div');
            actions.className = 'toolbar';
            var toList = document.createElement('button');
            toList.type = 'button';
            toList.textContent = '去列表查看';
            toList.addEventListener('click', function () {
                loadTickets(0);
            });
            actions.appendChild(toList);
            box.appendChild(actions);
            show('create-result', true);
        }
    }

    function renderCreated(ticket) {
        var box = element('create-result');
        if (!box) {
            return;
        }
        box.replaceChildren();
        var line = document.createElement('div');
        line.className = 'detail-row';
        var text = document.createElement('span');
        text.className = 'field-value';
        text.textContent = '已创建：' + ticket.id + ' · ' + ticket.title + ' · ' + ticket.status;
        line.appendChild(text);
        box.appendChild(line);

        var actions = document.createElement('div');
        actions.className = 'toolbar';
        var open = document.createElement('button');
        open.type = 'button';
        open.textContent = '打开详情';
        open.addEventListener('click', function () {
            openDetail(ticket.id);
        });
        actions.appendChild(open);
        var toList = document.createElement('button');
        toList.type = 'button';
        toList.textContent = '在列表中查看';
        toList.addEventListener('click', function () {
            loadTickets(0);
        });
        actions.appendChild(toList);
        box.appendChild(actions);
        show('create-result', true);
    }

    function submitCreate(event) {
        if (event) {
            event.preventDefault();
        }
        if (creating) {
            return;
        }

        var payload = {
            title: element('create-title') ? element('create-title').value : '',
            description: element('create-description') ? element('create-description').value : '',
            category: element('create-category') ? element('create-category').value : '',
            priority: element('create-priority') ? element('create-priority').value : '',
            requesterId: element('create-requester') ? element('create-requester').value : ''
        };

        show('create-error', false);
        show('create-result', false);
        setText('create-state', '提交中…');
        setCreateBusy(true);

        requestJson('/api/v1/tickets', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify(payload)
        }).then(function (result) {
            setCreateBusy(false);
            setText('create-state', '');

            if (result.transportError) {
                // 没有拿到响应：请求可能已经到达服务端并被处理，不能断言失败
                renderCreateIndeterminate('请求没有拿到响应（' + result.transportError + '）');
                return;
            }
            if (result.status >= 400 && result.status < 500) {
                // 明确的 4xx：服务端已拒绝，按失败显示（含原有 400 文案）
                show('create-error', true);
                setText('create-error', '创建失败：' + describeFailure(result));
                return;
            }
            if (result.status !== 201) {
                // 5xx 等：服务端可能已经处理过，结果不确定
                renderCreateIndeterminate('服务端返回 ' + describeFailure(result));
                return;
            }
            if (result.formatProblem) {
                renderCreateIndeterminate('响应体异常（' + result.formatProblem + '）');
                return;
            }
            var problem = missingField(result.body, ['id', 'title', 'status']);
            if (problem) {
                renderCreateIndeterminate('响应体异常（' + problem + '）');
                return;
            }
            renderCreated(result.body);
            var form = element('create-form');
            if (form) {
                form.reset();
            }
        });
    }

    // ---------- 事件绑定：加载时只调健康检查，其它都由点击触发 ----------

    document.addEventListener('DOMContentLoaded', function () {
        fillSelect('create-category', CATEGORIES);
        fillSelect('create-priority', PRIORITIES);
        loadHealth();

        var load = element('load-tickets');
        if (load) {
            load.addEventListener('click', function () {
                loadTickets(0);
            });
        }
        var prev = element('prev-page');
        if (prev) {
            prev.addEventListener('click', function () {
                loadTickets(Math.max(0, (lastPage === null ? 0 : lastPage) - 1));
            });
        }
        var next = element('next-page');
        if (next) {
            next.addEventListener('click', function () {
                loadTickets((lastPage === null ? 0 : lastPage) + 1);
            });
        }
        var form = element('create-form');
        if (form) {
            form.addEventListener('submit', submitCreate);
        }
    });
})();
