/*
 * FlowDesk 本机演示入口的脚本（FD-0023-A / FD-0023-B）。
 *
 * 约束：
 *   - 所有请求都用**相对路径**（同源），不写死主机名与端口；
 *   - 页面加载时只请求只读的健康端点，不发写请求，也不请求文档向量化、知识检索或模型调用类接口
 *     （本文件里也不写出它们的路径），因此加载页面不产生任何模型费用；
 *   - 工单列表、详情、新建都在用户点击之后才发请求；新建是唯一的写请求；
 *   - 用户输入与服务端数据一律只用 textContent 渲染：不把字符串当 HTML 插入，也不使用拼接 HTML 的写法；
 *   - 分类与优先级的取值与后端枚举一致：TicketCategory / TicketPriority（不修改 API 契约）。
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
    var creating = false;

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
     * @returns {Promise<{status:number, ok:boolean, body:object|null, transportError:string|null}>}
     */
    function requestJson(url, options) {
        return fetch(url, options)
            .then(function (response) {
                return response.text().then(function (text) {
                    var parsed = null;
                    if (text) {
                        try {
                            parsed = JSON.parse(text);
                        }
                        catch (error) {
                            parsed = { detail: '响应不是合法 JSON' };
                        }
                    }
                    return { status: response.status, ok: response.ok, body: parsed, transportError: null };
                });
            })
            .catch(function (error) {
                return { status: 0, ok: false, body: null, transportError: error.message };
            });
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
                var reported = result.body && result.body.status ? result.body.status : '(没有 status 字段)';
                setText('health-main', 'HTTP ' + result.status + ' · ' + reported);
                setClass('health-main', result.status === 200 && reported === 'UP' ? 'ok' : 'warn');
            });
    }

    // ---------- 工单列表（点击后；只读） ----------

    function renderPageMeta(result) {
        var body = result.body || {};
        var shown = body.items ? body.items.length : 0;
        setText('tickets-meta', '第 ' + (Number(body.page) + 1) + ' 页 / 共 ' + body.totalPages + ' 页，共 '
            + body.totalElements + ' 条（本页 ' + shown + ' 条）');
        var prev = element('prev-page');
        var next = element('next-page');
        if (prev) {
            prev.disabled = !body.hasPrevious;
        }
        if (next) {
            next.disabled = !body.hasNext;
        }
        lastPage = Number(body.page);
    }

    function renderTicketRows(items) {
        var table = element('tickets-table');
        var body = table ? table.querySelector('tbody') : null;
        if (!body) {
            return;
        }
        body.replaceChildren();
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
            body.appendChild(row);
        });
    }

    function loadTickets(page) {
        var target = typeof page === 'number' ? page : 0;
        show('tickets-error', false);
        show('tickets-table', false);
        show('tickets-state', true);
        setText('tickets-state', '加载中…');
        setText('tickets-meta', '');

        requestJson('/api/v1/tickets?page=' + target + '&size=' + PAGE_SIZE, { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                show('tickets-state', false);
                if (result.transportError) {
                    show('tickets-error', true);
                    setText('tickets-error', '请求失败（服务不可达）：' + result.transportError);
                    return;
                }
                if (result.status !== 200) {
                    show('tickets-error', true);
                    setText('tickets-error', '加载工单失败：' + describeFailure(result));
                    return;
                }
                var items = (result.body && result.body.items) ? result.body.items : [];
                renderPageMeta(result);
                if (items.length === 0) {
                    show('tickets-state', true);
                    setText('tickets-state', Number(result.body.totalElements) === 0
                        ? '还没有任何工单（空列表）'
                        : '本页没有工单');
                    return;
                }
                renderTicketRows(items);
                show('tickets-table', true);
            });
    }

    // ---------- 工单详情（点击后；只读） ----------

    function openDetail(ticketId) {
        show('detail-error', false);
        show('detail-body', false);
        show('detail-state', true);
        setText('detail-state', '加载中…');

        requestJson('/api/v1/tickets/' + encodeURIComponent(ticketId), { headers: { 'Accept': 'application/json' } })
            .then(function (result) {
                show('detail-state', false);
                if (result.transportError) {
                    show('detail-error', true);
                    setText('detail-error', '请求失败（服务不可达）：' + result.transportError);
                    return;
                }
                if (result.status === 404) {
                    show('detail-error', true);
                    setText('detail-error', '未找到该工单：' + describeFailure(result));
                    return;
                }
                if (result.status !== 200) {
                    show('detail-error', true);
                    setText('detail-error', '加载详情失败：' + describeFailure(result));
                    return;
                }
                renderDetail(result.body);
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

    function renderDetail(ticket) {
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
        appendRow(box, '版本', ticket.version);
        show('detail-body', true);
        var section = element('detail');
        if (section && section.scrollIntoView) {
            section.scrollIntoView({ block: 'start' });
        }
    }

    // ---------- 新建工单（唯一的写请求；防重复提交） ----------

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
        var button = element('create-submit');
        if (button) {
            button.disabled = busy;
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
                show('create-error', true);
                setText('create-error', '创建失败（服务不可达）：' + result.transportError);
                return;
            }
            if (result.status !== 201) {
                show('create-error', true);
                setText('create-error', '创建失败：' + describeFailure(result));
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
