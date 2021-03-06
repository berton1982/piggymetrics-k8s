package com.github.galleog.piggymetrics.account.service;

import static com.github.galleog.piggymetrics.account.domain.ItemType.EXPENSE;
import static com.github.galleog.piggymetrics.account.domain.ItemType.INCOME;
import static com.github.galleog.piggymetrics.account.domain.TimePeriod.DAY;
import static com.github.galleog.piggymetrics.account.domain.TimePeriod.MONTH;
import static com.github.galleog.protobuf.java.type.converter.Converters.bigDecimalConverter;
import static com.github.galleog.protobuf.java.type.converter.Converters.moneyConverter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.galleog.piggymetrics.account.AccountApplication;
import com.github.galleog.piggymetrics.account.config.GrpcTestConfig;
import com.github.galleog.piggymetrics.account.config.ReactorTestConfig;
import com.github.galleog.piggymetrics.account.domain.Account;
import com.github.galleog.piggymetrics.account.domain.Item;
import com.github.galleog.piggymetrics.account.domain.Saving;
import com.github.galleog.piggymetrics.account.grpc.AccountServiceProto;
import com.github.galleog.piggymetrics.account.grpc.AccountServiceProto.AccountUpdatedEvent;
import com.github.galleog.piggymetrics.account.grpc.AccountServiceProto.GetAccountRequest;
import com.github.galleog.piggymetrics.account.grpc.AccountServiceProto.ItemType;
import com.github.galleog.piggymetrics.account.grpc.AccountServiceProto.TimePeriod;
import com.github.galleog.piggymetrics.account.grpc.ReactorAccountServiceGrpc;
import com.github.galleog.piggymetrics.account.repository.AccountRepository;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.javamoney.moneta.Money;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.cloud.stream.test.binder.MessageCollector;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

/**
 * Tests for {@link AccountService}.
 */
@ActiveProfiles("test")
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {AccountApplication.class, AccountServiceTest.Config.class})
public class AccountServiceTest {
    private static final String USD = "USD";
    private static final String NAME = "test";
    private static final GetAccountRequest GET_ACCOUNT_REQUEST = GetAccountRequest.newBuilder()
            .setName(NAME)
            .build();
    private static final String NOTE = "note";
    private static final Item GROCERY = Item.builder()
            .title("Grocery")
            .moneyAmount(Money.of(10, USD))
            .period(DAY)
            .icon("meal")
            .type(EXPENSE)
            .build();
    private static final Item SALARY = Item.builder()
            .title("Salary")
            .moneyAmount(Money.of(9100, USD))
            .period(MONTH)
            .icon("wallet")
            .type(INCOME)
            .build();
    private static final Saving SAVING = Saving.builder()
            .moneyAmount(Money.of(BigDecimal.ZERO, USD))
            .interest(BigDecimal.ZERO)
            .build();

    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    @MockBean
    private AccountRepository repository;
    @Autowired
    private Source source;
    @Autowired
    private MessageCollector collector;
    private ReactorAccountServiceGrpc.ReactorAccountServiceStub accountServiceStub;
    private BlockingQueue<Message<?>> messages;

    @Before
    public void setUp() {
        ManagedChannel channel = grpcCleanup.register(InProcessChannelBuilder.forName(GrpcTestConfig.SERVICE_NAME)
                .directExecutor()
                .build());
        accountServiceStub = ReactorAccountServiceGrpc.newReactorStub(channel);

        messages = collector.forChannel(source.output());
        messages.poll();
    }

    /**
     * Test for {@link AccountService#getAccount(Mono)}.
     */
    @Test
    public void shouldGetAccount() {
        Account account = stubAccount();
        when(repository.getByName(NAME)).thenReturn(Optional.of(account));

        StepVerifier.create(accountServiceStub.getAccount(GET_ACCOUNT_REQUEST))
                .expectNextMatches(a -> {
                    assertThat(a.getName()).isEqualTo(NAME);
                    assertThat(a.getItemsList()).extracting(
                            AccountServiceProto.Item::getType,
                            AccountServiceProto.Item::getTitle,
                            AccountServiceProto.Item::getMoney,
                            AccountServiceProto.Item::getPeriod,
                            AccountServiceProto.Item::getIcon
                    ).containsExactlyInAnyOrder(
                            tuple(
                                    ItemType.EXPENSE,
                                    GROCERY.getTitle(),
                                    moneyConverter().convert(GROCERY.getMoneyAmount()),
                                    TimePeriod.DAY,
                                    GROCERY.getIcon()
                            ),
                            tuple(
                                    ItemType.INCOME,
                                    SALARY.getTitle(),
                                    moneyConverter().convert(SALARY.getMoneyAmount()),
                                    TimePeriod.MONTH,
                                    SALARY.getIcon()

                            )
                    );
                    assertThat(a.getSaving().getMoney()).isEqualTo(moneyConverter().convert(SAVING.getMoneyAmount()));
                    assertThat(a.getSaving().getInterest()).isEqualTo(bigDecimalConverter().convert(SAVING.getInterest()));
                    assertThat(a.getSaving().getDeposit()).isFalse();
                    assertThat(a.getSaving().getCapitalization()).isFalse();
                    assertThat(a.getUpdateTime()).isNotNull();
                    assertThat(a.getNote()).isEmpty();
                    return true;
                }).verifyComplete();
    }

    /**
     * Test for {@link AccountService#getAccount(Mono)} when no account is found.
     */
    @Test
    public void shouldFailToFindAccountWhenNotFound() {
        when(repository.getByName(NAME)).thenReturn(Optional.empty());

        StepVerifier.create(accountServiceStub.getAccount(GET_ACCOUNT_REQUEST))
                .expectErrorMatches(t -> {
                    assertThat(t).isInstanceOf(StatusRuntimeException.class);
                    assertThat(Status.fromThrowable(t).getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    return true;
                }).verify();
    }

    /**
     * Test for {@link AccountService#updateAccount(Mono)}.
     */
    @Test
    public void shouldUpdateAccount() {
        when(repository.update(any(Account.class)))
                .thenAnswer((Answer<Optional>) invocation -> Optional.of(invocation.getArgument(0)));

        Money savingAmount = Money.of(1500, USD);
        BigDecimal interest = BigDecimal.valueOf(3.32);
        AccountServiceProto.Saving saving = AccountServiceProto.Saving.newBuilder()
                .setMoney(moneyConverter().convert(savingAmount))
                .setInterest(bigDecimalConverter().convert(interest))
                .setDeposit(true)
                .build();

        Money rentAmount = Money.of(1200, USD);
        AccountServiceProto.Item rent = AccountServiceProto.Item.newBuilder()
                .setType(ItemType.EXPENSE)
                .setTitle("Rent")
                .setMoney(moneyConverter().convert(rentAmount))
                .setPeriod(TimePeriod.MONTH)
                .setIcon("home")
                .build();

        Money mealAmount = Money.of(20, USD);
        AccountServiceProto.Item meal = AccountServiceProto.Item.newBuilder()
                .setType(ItemType.EXPENSE)
                .setTitle("Meal")
                .setMoney(moneyConverter().convert(mealAmount))
                .setPeriod(TimePeriod.DAY)
                .setIcon("meal")
                .build();

        AccountServiceProto.Account account = AccountServiceProto.Account.newBuilder()
                .setName(NAME)
                .addItems(rent)
                .addItems(meal)
                .setSaving(saving)
                .setNote(NOTE)
                .build();

        StepVerifier.create(accountServiceStub.updateAccount(account))
                .expectNextMatches(a -> {
                    assertThat(a.getName()).isEqualTo(NAME);
                    assertThat(a.getItemsList()).containsExactlyInAnyOrder(rent, meal);
                    assertThat(a.getSaving()).isEqualTo(saving);
                    assertThat(a.getUpdateTime().isInitialized()).isTrue();
                    assertThat(a.getNote()).isEqualTo(NOTE);
                    return true;
                }).verifyComplete();

        verify(repository).update(argThat(a -> {
            assertThat(a.getName()).isEqualTo(NAME);
            assertThat(a.getItems()).extracting(
                    Item::getTitle, Item::getMoneyAmount, Item::getPeriod, Item::getIcon, Item::getType
            ).containsExactlyInAnyOrder(
                    tuple(rent.getTitle(), rentAmount, MONTH, rent.getIcon(), EXPENSE),
                    tuple(meal.getTitle(), mealAmount, DAY, meal.getIcon(), EXPENSE)
            );
            assertThat(a.getSaving().getMoneyAmount()).isEqualTo(savingAmount);
            assertThat(a.getSaving().getInterest()).isEqualTo(interest);
            assertThat(a.getSaving().isDeposit()).isTrue();
            assertThat(a.getSaving().isCapitalization()).isFalse();
            assertThat(a.getNote()).isEqualTo(NOTE);
            return true;
        }));

        assertThat(messages).extracting(msg -> (byte[]) msg.getPayload())
                .containsExactly(
                        AccountUpdatedEvent.newBuilder()
                                .setAccountName(NAME)
                                .addItems(rent)
                                .addItems(meal)
                                .setSaving(saving)
                                .setNote(NOTE)
                                .build()
                                .toByteArray()
                );
    }

    /**
     * Test for {@link AccountService#updateAccount(Mono)} when the account to be updated isn't found.
     */
    @Test
    public void shouldFailToUpdateAccountWhenNotFound() {
        when(repository.update(any(Account.class))).thenReturn(Optional.empty());

        Money savingAmount = Money.of(BigDecimal.ZERO, USD);
        AccountServiceProto.Saving saving = AccountServiceProto.Saving.newBuilder()
                .setMoney(moneyConverter().convert(savingAmount))
                .setInterest(bigDecimalConverter().convert(BigDecimal.ZERO))
                .build();
        AccountServiceProto.Account account = AccountServiceProto.Account.newBuilder()
                .setName(NAME)
                .setSaving(saving)
                .build();

        StepVerifier.create(accountServiceStub.updateAccount(account))
                .expectErrorMatches(t -> {
                    assertThat(t).isInstanceOf(StatusRuntimeException.class);
                    assertThat(Status.fromThrowable(t).getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    return true;
                }).verify();

        assertThat(messages).isEmpty();
    }

    /**
     * Test for {@link AccountService#updateAccount(Mono)} when account data are invalid.
     */
    @Test
    public void shouldFailToUpdateAccountWhenDataInvalid() {
        AccountServiceProto.Saving saving = AccountServiceProto.Saving.newBuilder()
                .setDeposit(true)
                .build();
        AccountServiceProto.Account account = AccountServiceProto.Account.newBuilder()
                .setName(NAME)
                .setSaving(saving)
                .build();

        StepVerifier.create(accountServiceStub.updateAccount(account))
                .expectErrorMatches(t -> {
                    assertThat(t).isInstanceOf(StatusRuntimeException.class);
                    assertThat(Status.fromThrowable(t).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    return true;
                }).verify();

        verify(repository, never()).update(any());
        assertThat(messages).isEmpty();
    }

    private Account stubAccount() {
        return Account.builder()
                .name(NAME)
                .item(GROCERY)
                .item(SALARY)
                .saving(SAVING)
                .build();
    }

    @Configuration
    @Import({GrpcTestConfig.class, ReactorTestConfig.class})
    @ImportAutoConfiguration(exclude = {JooqAutoConfiguration.class, LiquibaseAutoConfiguration.class})
    static class Config {
    }
}
