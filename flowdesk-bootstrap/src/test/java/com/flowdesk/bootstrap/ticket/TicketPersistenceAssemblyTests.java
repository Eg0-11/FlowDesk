package com.flowdesk.bootstrap.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowdesk.application.ticket.command.AssignTicketCommand;
import com.flowdesk.application.ticket.command.CreateTicketCommand;
import com.flowdesk.application.ticket.port.in.TicketCommandUseCase;
import com.flowdesk.application.ticket.port.in.TicketQueryUseCase;
import com.flowdesk.application.ticket.port.out.TicketIdGenerator;
import com.flowdesk.application.ticket.port.out.TicketRepository;
import com.flowdesk.application.ticket.port.out.TimeProvider;
import com.flowdesk.application.ticket.query.GetTicketQuery;
import com.flowdesk.application.ticket.service.TicketApplicationService;
import com.flowdesk.application.ticket.view.TicketView;
import com.flowdesk.domain.ticket.TicketCategory;
import com.flowdesk.domain.ticket.TicketPriority;
import com.flowdesk.domain.ticket.TicketStatus;
import com.flowdesk.domain.ticket.UserId;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 默认 profile 的持久化装配测试：真实启动应用、真实执行迁移、真实写库。
 *
 * <p>默认 profile 使用内存 H2（PostgreSQL 兼容模式），不需要任何数据库密码即可启动。</p>
 */
@SpringBootTest
class TicketPersistenceAssemblyTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TicketCommandUseCase ticketCommandUseCase;

    @Autowired
    private TicketQueryUseCase ticketQueryUseCase;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void defaultProfileSuppliesEveryTicketPortExactlyOnce() {
        assertThat(this.applicationContext.getBeanNamesForType(TicketRepository.class)).hasSize(1);
        assertThat(this.applicationContext.getBeanNamesForType(TicketIdGenerator.class)).hasSize(1);
        assertThat(this.applicationContext.getBeanNamesForType(TimeProvider.class)).hasSize(1);
        assertThat(this.applicationContext.getBeanNamesForType(Clock.class)).isNotEmpty();

        // 两个输入端口必须指向同一个用例服务实例，不存在重复 Bean
        assertThat(this.applicationContext.getBeanNamesForType(TicketApplicationService.class)).hasSize(1);
        assertThat(this.ticketCommandUseCase).isSameAs(this.ticketQueryUseCase);
        assertThat(this.ticketCommandUseCase).isInstanceOf(TicketApplicationService.class);
    }

    @Test
    void flywayHasMigratedAndTheTableExists() {
        Long applied = this.jdbcClient.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE")
                .query(Long.class)
                .single();
        assertThat(applied).as("Flyway 必须已成功应用至少一个迁移").isNotNull().isPositive();

        Long tables = this.jdbcClient
                .sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'tickets'")
                .query(Long.class)
                .single();
        assertThat(tables).as("tickets 表必须存在").isEqualTo(1L);
    }

    @Test
    void createAssignAndQueryReallyPersistToTheDatabase() {
        TicketView created = this.ticketCommandUseCase.create(new CreateTicketCommand(
                "无法登录办公系统", "用户反馈输入正确密码后仍提示认证失败。", TicketCategory.ACCOUNT_ACCESS,
                TicketPriority.P2, UserId.of("alice")));

        assertThat(created.status()).isEqualTo(TicketStatus.NEW);
        assertThat(created.version()).isZero();
        assertThat(created.assigneeId()).isEmpty();
        assertThat(rowCount(created)).as("创建必须真实落库").isEqualTo(1L);

        TicketView assigned = this.ticketCommandUseCase.assign(
                new AssignTicketCommand(created.id(), UserId.of("bob"), 0L));

        assertThat(assigned.status()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(assigned.assigneeId()).contains(UserId.of("bob"));
        assertThat(assigned.version()).isEqualTo(1);

        TicketView queried = this.ticketQueryUseCase.get(new GetTicketQuery(created.id()));

        assertThat(queried.id()).isEqualTo(created.id());
        assertThat(queried.assigneeId()).contains(UserId.of("bob"));
        assertThat(queried.version()).isEqualTo(1);

        Long persistedVersion = this.jdbcClient.sql("SELECT version FROM tickets WHERE id = ?")
                .param(1, created.id().value())
                .query(Long.class)
                .single();
        assertThat(persistedVersion).as("数据库中的版本必须与用例返回一致").isEqualTo(1L);
    }

    @Test
    void defaultProfileDoesNotWireAnyModelInfrastructure() {
        assertThat(this.applicationContext.getEnvironment().getProperty("flowdesk.ai.enabled", Boolean.class))
                .isFalse();
        assertThat(this.applicationContext.getBeanNamesForType(ChatModel.class))
                .as("默认 profile 不得创建任何对话模型，因此不可能出网")
                .isEmpty();
        assertThat(this.applicationContext.getBeanNamesForType(ChatClient.class)).isEmpty();
    }

    private long rowCount(TicketView view) {
        Long count = this.jdbcClient.sql("SELECT COUNT(*) FROM tickets WHERE id = ?")
                .param(1, view.id().value())
                .query(Long.class)
                .single();
        return count == null ? -1L : count;
    }
}
