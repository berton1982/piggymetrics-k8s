package com.github.galleog.piggymetrics.gateway.handler;

import static com.github.galleog.protobuf.java.type.converter.Converters.bigDecimalConverter;
import static com.github.galleog.protobuf.java.type.converter.Converters.dateConverter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doReturn;

import com.github.galleog.piggymetrics.gateway.model.statistics.DataPoint;
import com.github.galleog.piggymetrics.gateway.model.statistics.ItemMetric;
import com.github.galleog.piggymetrics.gateway.model.statistics.StatisticalMetric;
import com.github.galleog.piggymetrics.statistics.grpc.ReactorStatisticsServiceGrpc.StatisticsServiceImplBase;
import com.github.galleog.piggymetrics.statistics.grpc.StatisticsServiceProto;
import com.github.galleog.piggymetrics.statistics.grpc.StatisticsServiceProto.ItemType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;

/**
 * Tests for routing statistics requests.
 */
@RunWith(SpringRunner.class)
@WithMockUser(StatisticsRequestRouterTest.CURRENT_USER)
public class StatisticsRequestRouterTest extends BaseRouterTest {
    static final String CURRENT_USER = "test1";
    private static final String ACCOUNT_NAME = "test2";
    private static final LocalDate DAY_AGO = LocalDate.now().minusDays(1);
    private static final LocalDate WEEK_AGO = LocalDate.now().minusWeeks(1);
    private static final String GROCERY = "Grocery";
    private static final BigDecimal GROCERY_AMOUNT = BigDecimal.valueOf(100, 1);
    private static final String RENT = "Rent";
    private static final BigDecimal RENT_AMOUNT = BigDecimal.valueOf(200, 1);
    private static final String SALARY = "Salary";
    private static final BigDecimal SALARY_AMOUNT = BigDecimal.valueOf(3000, 1);
    private static final BigDecimal SAVING_AMOUNT = BigDecimal.valueOf(59000, 1);

    @Captor
    private ArgumentCaptor<Mono<StatisticsServiceProto.ListDataPointsRequest>> requestCaptor;

    private StatisticsServiceImplBase statisticsService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        statisticsService = spyGrpcService(StatisticsServiceImplBase.class, StatisticsHandler.STATISTICS_SERVICE);
    }

    /**
     * Test GET /statistics/current.
     */
    @Test
    public void shouldGetStatisticsForCurrentUser() {
        doReturn(Flux.just(
                stubDataPointProto(CURRENT_USER, DAY_AGO, ImmutableList.of(grocery(), salary()), SAVING_AMOUNT)
        )).when(statisticsService).listDataPoints(requestCaptor.capture());

        webClient.get()
                .uri("/statistics/current")
                .exchange()
                .expectBodyList(DataPoint.class)
                .value(list -> {
                    assertThat(list).extracting(DataPoint::getAccountName, DataPoint::getDate)
                            .containsExactlyInAnyOrder(tuple(CURRENT_USER, DAY_AGO));

                    DataPoint dataPoint = list.get(0);
                    assertThat(dataPoint.getMetrics()).extracting(
                            ItemMetric::getType, ItemMetric::getTitle, ItemMetric::getMoneyAmount
                    ).containsExactlyInAnyOrder(
                            tuple(ItemType.EXPENSE, GROCERY, GROCERY_AMOUNT),
                            tuple(ItemType.INCOME, SALARY, SALARY_AMOUNT)
                    );
                    assertThat(dataPoint.getStatistics()).containsOnly(
                            new SimpleEntry<>(StatisticalMetric.EXPENSES_AMOUNT, GROCERY_AMOUNT),
                            new SimpleEntry<>(StatisticalMetric.INCOMES_AMOUNT, SALARY_AMOUNT),
                            new SimpleEntry<>(StatisticalMetric.SAVING_AMOUNT, SAVING_AMOUNT)
                    );
                });

        StepVerifier.create(requestCaptor.getValue())
                .expectNextMatches(req -> CURRENT_USER.equals(req.getAccountName()))
                .verifyComplete();
    }

    /**
     * Test for GET /statistics/test2.
     */
    @Test
    public void shouldGetStatisticsByAccountName() {
        doReturn(Flux.just(
                stubDataPointProto(ACCOUNT_NAME, DAY_AGO, ImmutableList.of(grocery(), rent(), salary()), SAVING_AMOUNT),
                stubDataPointProto(ACCOUNT_NAME, WEEK_AGO, ImmutableList.of(salary()), BigDecimal.ZERO)
        )).when(statisticsService).listDataPoints(requestCaptor.capture());

        webClient.get()
                .uri("/statistics/" + ACCOUNT_NAME)
                .exchange()
                .expectBodyList(DataPoint.class)
                .value(list -> {
                    assertThat(list).extracting(DataPoint::getAccountName, DataPoint::getDate)
                            .containsExactlyInAnyOrder(tuple(ACCOUNT_NAME, DAY_AGO), tuple(ACCOUNT_NAME, WEEK_AGO));

                    DataPoint dayAgo = list.stream()
                            .filter(dataPoint -> DAY_AGO.equals(dataPoint.getDate()))
                            .findFirst().get();
                    assertThat(dayAgo.getMetrics()).extracting(
                            ItemMetric::getType, ItemMetric::getTitle, ItemMetric::getMoneyAmount
                    ).containsExactlyInAnyOrder(
                            tuple(ItemType.EXPENSE, GROCERY, GROCERY_AMOUNT),
                            tuple(ItemType.EXPENSE, RENT, RENT_AMOUNT),
                            tuple(ItemType.INCOME, SALARY, SALARY_AMOUNT)
                    );
                    assertThat(dayAgo.getStatistics()).containsOnly(
                            new SimpleEntry<>(StatisticalMetric.EXPENSES_AMOUNT, GROCERY_AMOUNT.add(RENT_AMOUNT)),
                            new SimpleEntry<>(StatisticalMetric.INCOMES_AMOUNT, SALARY_AMOUNT),
                            new SimpleEntry<>(StatisticalMetric.SAVING_AMOUNT, SAVING_AMOUNT)
                    );

                    DataPoint weekAgo = list.stream()
                            .filter(dataPoint -> WEEK_AGO.equals(dataPoint.getDate()))
                            .findFirst().get();
                    assertThat(weekAgo.getMetrics()).extracting(
                            ItemMetric::getType, ItemMetric::getTitle, ItemMetric::getMoneyAmount
                    ).containsExactly(
                            tuple(ItemType.INCOME, SALARY, SALARY_AMOUNT)
                    );
                    assertThat(weekAgo.getStatistics()).containsOnly(
                            new SimpleEntry<>(StatisticalMetric.EXPENSES_AMOUNT, BigDecimal.ZERO),
                            new SimpleEntry<>(StatisticalMetric.INCOMES_AMOUNT, SALARY_AMOUNT),
                            new SimpleEntry<>(StatisticalMetric.SAVING_AMOUNT, BigDecimal.ZERO)
                    );
                });

        StepVerifier.create(requestCaptor.getValue())
                .expectNextMatches(req -> ACCOUNT_NAME.equals(req.getAccountName()))
                .verifyComplete();
    }

    private StatisticsServiceProto.ItemMetric grocery() {
        return StatisticsServiceProto.ItemMetric.newBuilder()
                .setType(ItemType.EXPENSE)
                .setTitle(GROCERY)
                .setMoneyAmount(bigDecimalConverter().convert(GROCERY_AMOUNT))
                .build();
    }

    private StatisticsServiceProto.ItemMetric rent() {
        return StatisticsServiceProto.ItemMetric.newBuilder()
                .setType(ItemType.EXPENSE)
                .setTitle(RENT)
                .setMoneyAmount(bigDecimalConverter().convert(RENT_AMOUNT))
                .build();
    }

    private StatisticsServiceProto.ItemMetric salary() {
        return StatisticsServiceProto.ItemMetric.newBuilder()
                .setType(ItemType.INCOME)
                .setTitle(SALARY)
                .setMoneyAmount(bigDecimalConverter().convert(SALARY_AMOUNT))
                .build();
    }

    private StatisticsServiceProto.DataPoint stubDataPointProto(String accountName, LocalDate date,
                                                                List<StatisticsServiceProto.ItemMetric> metrics,
                                                                BigDecimal saving) {
        return StatisticsServiceProto.DataPoint.newBuilder()
                .setAccountName(accountName)
                .setDate(dateConverter().convert(date))
                .addAllMetrics(metrics)
                .putAllStatistics(ImmutableMap.of(
                        StatisticalMetric.INCOMES_AMOUNT.name(),
                        bigDecimalConverter().convert(
                                metrics.stream()
                                        .filter(metric -> ItemType.INCOME.equals(metric.getType()))
                                        .map(metric -> bigDecimalConverter().reverse().convert(metric.getMoneyAmount()))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        ),
                        StatisticalMetric.EXPENSES_AMOUNT.name(),
                        bigDecimalConverter().convert(
                                metrics.stream()
                                        .filter(metric -> ItemType.EXPENSE.equals(metric.getType()))
                                        .map(metric -> bigDecimalConverter().reverse().convert(metric.getMoneyAmount()))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        ),
                        StatisticalMetric.SAVING_AMOUNT.name(),
                        bigDecimalConverter().convert(saving)
                )).build();
    }
}