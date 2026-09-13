package com.flowdesk.bootstrap.ticket;

import com.flowdesk.application.ticket.port.out.TicketIdGenerator;
import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TimeProvider;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工单应用服务的装配。
 *
 * <p>{@link TicketApplicationService} 同时实现 {@code TicketCommandUseCase} 与
 * {@code TicketQueryUseCase}，因此注册为它自身的 Bean 后，输入适配器按任一接口类型注入都能拿到同一实例，
 * 不需要两个 Bean，也不会出现重复注册。</p>
 *
 * <p>依赖方向保持单向：应用层不认识 Spring，基础设施层提供三个输出端口实现，
 * bootstrap 只做最后的拼装。这里不依赖任何具体 JDBC 实现。</p>
 */
@Configuration(proxyBeanMethods = false)
public class TicketApplicationConfiguration {

    /**
     * @param ticketRepository  工单存储端口（由基础设施层提供）
     * @param ticketIdGenerator 标识生成端口
     * @param timeProvider      时间端口
     * @return 工单用例服务
     */
    @Bean
    public TicketApplicationService ticketApplicationService(TicketRepository ticketRepository,
            TicketIdGenerator ticketIdGenerator, TimeProvider timeProvider) {
        return new TicketApplicationService(ticketRepository, ticketIdGenerator, timeProvider);
    }
}
