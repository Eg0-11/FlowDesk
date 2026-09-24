/*
 * FlowDesk 本机演示入口的脚本。
 *
 * 约束（FD-0023-A）：
 *   - 所有请求都用**相对路径**（同源），不写死主机名与端口；
 *   - 页面加载时只请求只读的健康端点；
 *   - 只用两个只读接口：健康检查与工单列表。**不**请求文档向量化、知识检索或模型调用类接口
 *     （这三类要么会写数据、要么可能产生供应商费用，本页一概不碰；本文件里也不写出它们的路径）；
 *   - 不依赖任何前端框架或外部 CDN，离线也能用。
 */
(function () {
    'use strict';

    function element(id) {
        return document.getElementById(id);
    }

    function setClass(id, className) {
        var target = element(id);
        if (target) {
            target.className = className;
        }
    }

    function loadHealth() {
        // 只读健康端点：不写数据，不涉及任何模型。
        fetch('/actuator/health', { headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.json().then(function (body) {
                    return { status: response.status, body: body };
                });
            })
            .then(function (result) {
                var reported = result.body && result.body.status ? result.body.status : '(没有 status 字段)';
                var target = element('health-main');
                if (target) {
                    target.textContent = 'HTTP ' + result.status + ' · ' + reported;
                }
                setClass('health-main', result.status === 200 && reported === 'UP' ? 'ok' : 'warn');
            })
            .catch(function (error) {
                var target = element('health-main');
                if (target) {
                    target.textContent = '请求失败：' + error.message;
                }
                setClass('health-main', 'failed');
            });
    }

    function fillRows(table, items) {
        var body = table ? table.querySelector('tbody') : null;
        if (!body) {
            return;
        }
        body.innerHTML = '';
        items.forEach(function (item) {
            var row = document.createElement('tr');
            [item.id, item.title, item.status, item.priority, item.requesterId].forEach(function (value) {
                var cell = document.createElement('td');
                cell.textContent = (value === undefined || value === null) ? '' : String(value);
                row.appendChild(cell);
            });
            body.appendChild(row);
        });
    }

    function loadTickets() {
        var meta = element('tickets-meta');
        var errorBox = element('tickets-error');
        var table = element('tickets-table');

        if (errorBox) {
            errorBox.hidden = true;
        }
        if (meta) {
            meta.textContent = '加载中…';
        }

        // 只读列表：相对路径，同源。
        fetch('/api/v1/tickets?size=10', { headers: { 'Accept': 'application/json' } })
            .then(function (response) {
                return response.json().then(function (body) {
                    return { status: response.status, body: body };
                });
            })
            .then(function (result) {
                if (result.status !== 200) {
                    if (meta) {
                        meta.textContent = '';
                    }
                    if (errorBox) {
                        errorBox.hidden = false;
                        errorBox.textContent = 'HTTP ' + result.status + '：' + JSON.stringify(result.body);
                    }
                    if (table) {
                        table.hidden = true;
                    }
                    return;
                }

                var items = result.body.items || [];
                fillRows(table, items);
                if (table) {
                    table.hidden = items.length === 0;
                }
                if (meta) {
                    meta.textContent = items.length === 0
                        ? '还没有工单（共 0 条）'
                        : '共 ' + result.body.totalElements + ' 条，本页显示 ' + items.length + ' 条';
                }
            })
            .catch(function (error) {
                if (meta) {
                    meta.textContent = '';
                }
                if (errorBox) {
                    errorBox.hidden = false;
                    errorBox.textContent = '请求失败：' + error.message;
                }
            });
    }

    document.addEventListener('DOMContentLoaded', function () {
        loadHealth();

        var button = element('load-tickets');
        if (button) {
            button.addEventListener('click', loadTickets);
        }
    });
})();
