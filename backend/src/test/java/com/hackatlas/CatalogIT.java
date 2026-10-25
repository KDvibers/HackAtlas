package com.hackatlas;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.hackatlas.catalog.CatalogDemoSeeder;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogIT {

    private static final String AI = "39287368-5259-5235-a1c4-25b4e1e8421e";
    private static final String FINTECH = "9ee483ad-ff37-5dae-8da1-ae901ea7d27f";
    private static final String GAME = "9756e7f3-5796-5d69-93cf-a6a6cb8ebd33";
    private static final String GREEN = "fc6f0e31-af57-52ac-a736-3d0cce49b3fa";
    private static final String CLOSED = "ff4ed922-d2c6-5e7e-b7f3-c532efe4b34d";
    private static final String EXTRA = "00000000-0000-4000-8000-000000000001";
    private static final String LIST = "/api/v1/hackathons";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<ObjectNode> responses = new ArrayList<>();

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CatalogDemoSeeder seeder;
    @Autowired
    private JsonMapper json;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void restoreCanonicalDemoData() {
        jdbc.execute("""
                truncate table hackatlas.catalog_hackathon_topic,
                    hackatlas.catalog_hackathon, hackatlas.catalog_tag cascade
                """);
        seeder.seed();
    }

    @AfterAll
    void exportActualHttpResponsesForOpenApiValidation() throws Exception {
        try {
            var output = Path.of("target/catalog-contract-responses.json");
            Files.createDirectories(output.getParent());
            json.writeValue(output.toFile(), Map.of("source", "CatalogIT HTTP", "responses", responses));
        } finally {
            http.close();
        }
    }

    @Test
    void newestPageAndAiDetailMatchTheSharedFixturesWithFixedClock() throws Exception {
        assertThat(get(LIST + "?sort=NEWEST", 200)).isEqualTo(fixture("catalog-page.json"));
        assertThat(detail(AI)).isEqualTo(fixture("hackathon-detail.json"));
    }

    @Test
    void demoSeedIsIdempotentAndDoesNotOverwriteExistingContent() throws Exception {
        seeder.seed();
        assertThat(get(LIST + "?sort=NEWEST", 200)).isEqualTo(fixture("catalog-page.json"));
        update(AI, "title = ?", "Locally corrected title");
        seeder.seed();
        assertThat(detail(AI).path("title").stringValue()).isEqualTo("Locally corrected title");
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.catalog_hackathon", Integer.class))
                .isEqualTo(5);
    }

    static Stream<Arguments> searchableFields() {
        return Stream.of(
                Arguments.of("aI sPrInT", List.of(AI)),
                Arguments.of("COMMUNITY", List.of(AI, FINTECH, GAME, GREEN, CLOSED)),
                Arguments.of("AI / ML", List.of(AI)),
                Arguments.of("АСТАНА", List.of(FINTECH))
        );
    }

    @ParameterizedTest
    @MethodSource("searchableFields")
    void searchesTitleOrganizerTopicLabelAndCityCaseInsensitively(String query, List<String> expected)
            throws Exception {
        assertThat(ids(list("query", query))).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void blankSearchIsEquivalentToNoSearch() throws Exception {
        assertThat(list("query", " \t  ")).isEqualTo(get(LIST, 200));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "\\", "' OR 1=1 --"})
    void searchTreatsSqlAndLikeMetacharactersLiterally(String literal) throws Exception {
        update(AI, "title = ?", "Literal " + literal + " example");
        assertThat(ids(list("query", literal))).containsExactly(AI);
        assertThat(get(LIST, 200).path("totalElements").intValue()).isEqualTo(5);
    }

    static Stream<Arguments> nullByteFilters() {
        var nullByte = Character.toString(0);
        return Stream.of(
                Arguments.of("query", "AI" + nullByte + " Sprint"),
                Arguments.of("city", "Ас" + nullByte + "тана"),
                Arguments.of("topic", "ai" + nullByte + "-ml")
        );
    }

    @ParameterizedTest
    @MethodSource("nullByteFilters")
    void embeddedNullByteIsAnUnmatchedLiteralRatherThanADatabaseError(String field, String value)
            throws Exception {
        var page = list(field, value);
        assertThat(ids(page)).isEmpty();
        assertThat(page.path("totalElements").intValue()).isZero();
        assertThat(page.path("totalPages").intValue()).isZero();
        assertThat(page.path("hasNext").booleanValue()).isFalse();
    }

    @Test
    void combinesFiltersWithAndAndUsesLocationRatherThanEligibility() throws Exception {
        var filters = Map.of(
                "query", "hack", "format", "OFFLINE", "countryCode", "KZ", "city", "Астана",
                "topic", "fintech", "registrationStatus", "OPEN", "individualApplicationAllowed", "true",
                "startsFrom", "2026-11-20", "startsTo", "2026-11-20"
        );
        assertThat(ids(get(listPath(filters), 200))).containsExactly(FINTECH);
        assertThat(ids(list("countryCode", "KZ"))).containsExactly(FINTECH);
        assertThat(ids(get(listPath(Map.of("countryCode", "KZ", "format", "ONLINE")), 200))).isEmpty();
        assertThat(detail(AI).path("eligibility").path("allowedCountryCodes").isEmpty()).isTrue();
    }

    @Test
    void nullableIndividualApplicationIsNeitherTrueNorFalse() throws Exception {
        assertThat(ids(list("individualApplicationAllowed", "true")))
                .containsExactlyInAnyOrder(AI, FINTECH, GAME, CLOSED);
        assertThat(ids(list("individualApplicationAllowed", "false"))).isEmpty();
        update(GAME, "individual_application_allowed = ?", false);
        assertThat(ids(list("individualApplicationAllowed", "false"))).containsExactly(GAME);
        assertThat(ids(list("individualApplicationAllowed", "true"))).doesNotContain(GAME, GREEN);
    }

    @Test
    void dateBoundsAreInclusiveStartDatesNotEventOverlapAndExcludeUnknownStart() throws Exception {
        assertThat(ids(get(listPath(Map.of("startsFrom", "2026-11-12", "startsTo", "2026-11-15")), 200)))
                .containsExactlyInAnyOrder(AI, CLOSED);
        assertThat(ids(list("startsFrom", "2026-11-20"))).containsExactlyInAnyOrder(FINTECH, GAME, GREEN);
        assertThat(ids(get(listPath(Map.of("startsFrom", "2026-11-13", "startsTo", "2026-11-13")), 200)))
                .isEmpty();
        time(AI, "starts", null, null, null, null, null);
        assertThat(ids(get(LIST, 200))).contains(AI);
        assertThat(ids(list("startsFrom", "2026-01-01"))).doesNotContain(AI);
    }

    @Test
    void pagingDefaultsAndEmptyPagesKeepTheRealTotals() throws Exception {
        var defaultPage = get(LIST, 200);
        assertThat(defaultPage.path("page").intValue()).isZero();
        assertThat(defaultPage.path("size").intValue()).isEqualTo(20);
        assertThat(defaultPage.path("totalElements").intValue()).isEqualTo(5);
        assertThat(defaultPage.path("asOf").stringValue()).isEqualTo(IntegrationTestConfiguration.NOW.toString());

        var first = get(LIST + "?size=2", 200);
        assertThat(ids(first)).hasSize(2);
        assertThat(first.path("totalPages").intValue()).isEqualTo(3);
        assertThat(first.path("hasNext").booleanValue()).isTrue();
        var last = get(LIST + "?page=2&size=2", 200);
        assertThat(ids(last)).hasSize(1);
        assertThat(last.path("hasNext").booleanValue()).isFalse();
        var beyond = get(LIST + "?page=9&size=2", 200);
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.path("totalElements").intValue()).isEqualTo(5);
        assertThat(beyond.path("totalPages").intValue()).isEqualTo(3);
        assertThat(beyond.path("hasNext").booleanValue()).isFalse();
        assertThat(list("query", "no such event 987654321")).isEqualTo(fixture("catalog-empty.json"));
    }

    @Test
    void pageBeyondJavaIntegerAndLongLimitsReturnsAnEmptyPageWithoutInventedLimits() throws Exception {
        var pageNumber = new BigInteger("123456789012345678901234567890");
        var page = get(LIST + "?page=" + pageNumber + "&size=2", 200);
        assertThat(page.path("page").bigIntegerValue()).isEqualTo(pageNumber);
        assertThat(ids(page)).isEmpty();
        assertThat(page.path("totalElements").intValue()).isEqualTo(5);
        assertThat(page.path("totalPages").intValue()).isEqualTo(3);
        assertThat(page.path("hasNext").booleanValue()).isFalse();
    }

    @Test
    void defaultDeadlineSortIsStableAcrossPageBoundariesAndPutsUnknownLast() throws Exception {
        var expected = List.of(CLOSED, GAME, FINTECH, AI, GREEN);
        assertThat(ids(get(LIST, 200))).containsExactlyElementsOf(expected);
        var paged = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            paged.addAll(ids(get(LIST + "?page=" + page + "&size=2", 200)));
        }
        assertThat(paged).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void deadlineSortOrdersKnownTimeBeforeDateOnlyAndBreaksTiesById() throws Exception {
        time(AI, "deadline", "MINUTE", "2026-10-30", "08:00", "Asia/Almaty", "2026-10-30T03:00:00Z");
        cloneEvent(AI, EXTRA);
        assertThat(ids(get(LIST, 200))).containsExactly(CLOSED, EXTRA, AI, GAME, FINTECH, GREEN);
    }

    @Test
    void startSortUsesPublishedLocalDateAndTimeRatherThanInstant() throws Exception {
        time(AI, "starts", "MINUTE", "2026-11-12", "08:00", "Asia/Almaty", "2026-11-12T03:00:00Z");
        time(FINTECH, "starts", "MINUTE", "2026-11-12", "04:00", "UTC", "2026-11-12T04:00:00Z");
        time(GAME, "starts", "DATE", "2026-11-12", null, "Asia/Almaty", null);
        time(GREEN, "starts", "DATE", "2026-11-13", null, null, null);
        time(CLOSED, "starts", null, null, null, null, null);
        cloneEvent(AI, EXTRA);
        assertThat(ids(list("sort", "START_ASC"))).containsExactly(FINTECH, EXTRA, AI, GAME, GREEN, CLOSED);
    }

    @Test
    void newestUsesPublishedAtRatherThanUpdatedAtAndBreaksTiesById() throws Exception {
        update(AI, "published_at = ?::timestamptz", "2026-10-01T00:00:00Z");
        update(FINTECH, "published_at = ?::timestamptz", "2026-10-10T00:00:00Z");
        update(GAME, "published_at = ?::timestamptz", "2026-10-10T00:00:00Z");
        update(GREEN, "published_at = ?::timestamptz", "2026-10-09T00:00:00Z");
        update(CLOSED, "published_at = ?::timestamptz", "2026-10-08T00:00:00Z");
        assertThat(ids(list("sort", "NEWEST"))).containsExactly(GAME, FINTECH, GREEN, CLOSED, AI);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ARCHIVED"})
    void unpublishedEventsAreHiddenFromListAndDetail(String publicationStatus) throws Exception {
        update(AI, "publication_status = ?", publicationStatus);
        assertThat(ids(get(LIST, 200))).doesNotContain(AI);
        assertProblem(get(LIST + "/" + AI, 404), 404, "NOT_FOUND", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENDED", "CANCELLED"})
    void publishedEndedAndCancelledRemainAccessibleByIdButNotInList(String eventStatus) throws Exception {
        update(AI, "event_status = ?", eventStatus);
        assertThat(ids(get(LIST, 200))).doesNotContain(AI);
        assertThat(detail(AI).path("eventStatus").stringValue()).isEqualTo(eventStatus);
        assertThat(detail(AI).path("registrationStatus").stringValue()).isEqualTo("CLOSED");
    }

    @Test
    void metadataContainsVisibleLocationAndTopicsAndAllowedProfileInterests() throws Exception {
        var metadata = get("/api/v1/metadata/filters", 200);
        assertThat(metadata.path("countries")).isEqualTo(fixture("filter-metadata.json").path("countries"));
        assertThat(metadata.path("cities")).isEqualTo(fixture("filter-metadata.json").path("cities"));
        assertThat(stringField(metadata.path("topics"), "id"))
                .containsExactlyInAnyOrder("ai-ml", "fintech", "gamedev", "greentech", "web");
        assertThat(stringField(metadata.path("interests"), "id")).contains("java", "kotlin", "backend");

        cloneEvent(AI, EXTRA);
        update(EXTRA, "publication_status = ?, country_code = ?, city = ?", "DRAFT", "US", "Hidden City");
        jdbc.update("insert into hackatlas.catalog_tag (id,label,interest_allowed) values (?,?,false)",
                "hidden-topic", "Hidden Topic");
        jdbc.update("insert into hackatlas.catalog_hackathon_topic values (?,?)", uuid(EXTRA), "hidden-topic");
        var after = get("/api/v1/metadata/filters", 200);
        assertThat(stringField(after.path("topics"), "id")).doesNotContain("hidden-topic");
        assertThat(stringField(after.path("countries"), "code")).doesNotContain("US");
        assertThat(stringField(after.path("cities"), "name")).doesNotContain("Hidden City");
    }

    @Test
    void requiredNullsDecimalMoneyAndUnknownVersusUnrestrictedEligibilitySurviveHttp() throws Exception {
        update(GREEN, "allowed_country_codes = null, registration_url = null, rules_url = null");
        var green = detail(GREEN);
        for (var field : List.of("registrationDeadline", "imageUrl", "lastVerifiedAt", "registrationUrl", "rulesUrl")) {
            assertThat(green.has(field)).as(field + " must be present").isTrue();
            assertThat(green.path(field).isNull()).as(field).isTrue();
        }
        assertThat(green.path("eligibility").path("allowedCountryCodes").isNull()).isTrue();
        assertThat(detail(AI).path("eligibility").path("allowedCountryCodes").isArray()).isTrue();
        assertThat(detail(AI).path("eligibility").path("allowedCountryCodes").isEmpty()).isTrue();
        assertThat(green.path("prize").path("totalAmount").isNull()).isTrue();
        assertThat(green.path("prize").path("status").stringValue()).isEqualTo("UNKNOWN");
        assertThat(detail(AI).path("prize").path("totalAmount").stringValue()).isEqualTo("10000.00");
        update(AI, "prize_total_amount = ?::numeric", "12345678901234567890.123456");
        assertThat(detail(AI).path("prize").path("totalAmount").stringValue())
                .isEqualTo("12345678901234567890.123456");
    }

    @Test
    void dateOnlyAndMinutePrecisionKeepTheirOriginalTimeAndZoneInformation() throws Exception {
        var ai = detail(AI).path("starts");
        assertThat(ai).isEqualTo(fixture("hackathon-detail.json").path("starts"));
        var fintech = detail(FINTECH).path("starts");
        assertThat(fintech.path("precision").stringValue()).isEqualTo("DATE");
        assertThat(fintech.path("time").isNull()).isTrue();
        assertThat(fintech.path("instant").isNull()).isTrue();
        assertThat(fintech.path("timeZone").stringValue()).isEqualTo("Asia/Almaty");
    }

    static Stream<Arguments> deadlineCases() {
        return Stream.of(
                Arguments.of("DATE", "2026-10-12", null, "Asia/Almaty", null, "OPEN"),
                Arguments.of("DATE", "2026-10-11", null, "Asia/Almaty", null, "CLOSED"),
                Arguments.of("DATE", "2026-10-11", null, null, null, "OPEN"),
                Arguments.of("DATE", "2026-10-12", null, "Pacific/Kiritimati", null, "CLOSED"),
                Arguments.of("MINUTE", "2026-10-12", "09:59", "UTC", "2026-10-12T09:59:00Z", "CLOSED"),
                Arguments.of("MINUTE", "2026-10-12", "10:00", "UTC", "2026-10-12T10:00:00Z", "CLOSED"),
                Arguments.of("MINUTE", "2026-10-12", "10:01", "UTC", "2026-10-12T10:01:00Z", "OPEN")
        );
    }

    @ParameterizedTest
    @MethodSource("deadlineCases")
    void registrationUsesFixedClockPrecisionAndLocalZoneWithoutInventingTime(
            String precision, String date, String time, String zone, String instant, String expected
    ) throws Exception {
        time(AI, "deadline", precision, date, time, zone, instant);
        assertThat(detail(AI).path("registrationStatus").stringValue()).isEqualTo(expected);
        assertThat(ids(list("registrationStatus", expected))).contains(AI);
        assertThat(ids(list("registrationStatus", expected.equals("OPEN") ? "CLOSED" : "OPEN"))).doesNotContain(AI);
    }

    static Stream<Arguments> startCases() {
        return Stream.of(
                Arguments.of("DATE", "2026-10-12", null, "Asia/Almaty", null, "ONGOING"),
                Arguments.of("DATE", "2026-10-11", null, null, null, "SCHEDULED"),
                Arguments.of("MINUTE", "2026-10-12", "10:00", "UTC", "2026-10-12T10:00:00Z", "ONGOING"),
                Arguments.of("MINUTE", "2026-10-12", "10:01", "UTC", "2026-10-12T10:01:00Z", "SCHEDULED")
        );
    }

    @ParameterizedTest
    @MethodSource("startCases")
    void eventStartUsesInclusiveKnownBoundariesWithoutInventingAnUnknownZone(
            String precision, String date, String time, String zone, String instant, String expected
    ) throws Exception {
        time(AI, "starts", precision, date, time, zone, instant);
        assertThat(detail(AI).path("eventStatus").stringValue()).isEqualTo(expected);
        var page = get(LIST, 200);
        assertThat(ids(page)).contains(AI);
        for (var item : page.path("items")) {
            if (AI.equals(item.path("id").stringValue())) {
                assertThat(item.path("eventStatus").stringValue()).isEqualTo(expected);
            }
        }
    }

    static Stream<Arguments> endCases() {
        return Stream.of(
                Arguments.of("DATE", "2026-10-11", null, "Asia/Almaty", null, "ENDED"),
                Arguments.of("DATE", "2026-10-12", null, "Asia/Almaty", null, "ONGOING"),
                Arguments.of("DATE", "2026-10-11", null, null, null, "ONGOING"),
                Arguments.of("MINUTE", "2026-10-12", "10:00", "UTC", "2026-10-12T10:00:00Z", "ENDED"),
                Arguments.of("MINUTE", "2026-10-12", "10:01", "UTC", "2026-10-12T10:01:00Z", "ONGOING")
        );
    }

    @ParameterizedTest
    @MethodSource("endCases")
    void eventCompletionUsesPrecisionAndDoesNotHideEventsEarly(
            String precision, String date, String time, String zone, String instant, String expected
    ) throws Exception {
        time(AI, "starts", "DATE", "2026-10-10", null, "Asia/Almaty", null);
        time(AI, "ends", precision, date, time, zone, instant);
        assertThat(detail(AI).path("eventStatus").stringValue()).isEqualTo(expected);
        assertThat(ids(get(LIST, 200)).contains(AI)).isEqualTo(!expected.equals("ENDED"));
    }

    @Test
    void manualPostponementAndExplicitRegistrationStatesRemainAuthoritative() throws Exception {
        time(AI, "ends", "DATE", "2026-10-01", null, "Asia/Almaty", null);
        time(AI, "deadline", "DATE", "2026-10-01", null, "Asia/Almaty", null);
        update(AI, "event_status = ?", "POSTPONED");
        assertThat(detail(AI).path("eventStatus").stringValue()).isEqualTo("POSTPONED");
        assertThat(detail(AI).path("registrationStatus").stringValue()).isEqualTo("OPEN");
        assertThat(ids(get(LIST, 200))).contains(AI);
        update(AI, "registration_status = ?", "CLOSED");
        assertThat(detail(AI).path("registrationStatus").stringValue()).isEqualTo("CLOSED");
        update(FINTECH, "registration_status = ?", "UPCOMING");
        assertThat(ids(list("registrationStatus", "UPCOMING"))).containsExactly(FINTECH);
        assertThat(ids(list("registrationStatus", "UNKNOWN"))).containsExactly(GREEN);
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of("page", "-1"), Arguments.of("page", "1.5"), Arguments.of("page", "abc"),
                Arguments.of("size", "0"), Arguments.of("size", "51"), Arguments.of("size", "999999999999999999999"),
                Arguments.of("sort", "title; DROP TABLE users"), Arguments.of("format", "REMOTE"),
                Arguments.of("registrationStatus", "ACTIVE"), Arguments.of("individualApplicationAllowed", "yes"),
                Arguments.of("countryCode", "kaz"), Arguments.of("startsFrom", "2026-02-30"),
                Arguments.of("startsTo", "not-a-date"), Arguments.of("query", "x".repeat(121)),
                Arguments.of("city", "x".repeat(101)), Arguments.of("topic", "x".repeat(51))
        );
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void invalidQueryReturnsSafeProblemWithFieldErrors(String field, String value) throws Exception {
        assertProblem(get(listPath(Map.of(field, value)), 400), 400, "VALIDATION_ERROR", field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"=7&=V7", "%00=x", "%13qv=x", "unknown=1&unknown=2", "size=2&size=3"})
    void malformedParameterNamesAndRepeatedValuesReturnValidationProblems(String rawQuery) throws Exception {
        assertProblem(get(LIST + "?" + rawQuery, 400), 400, "VALIDATION_ERROR", null);
    }

    @Test
    void reversedDateRangeMalformedIdAndUnknownIdHaveContractErrors() throws Exception {
        assertProblem(get(LIST + "?startsFrom=2026-11-21&startsTo=2026-11-20", 400),
                400, "VALIDATION_ERROR", "startsTo");
        assertProblem(get(LIST + "/not-a-uuid", 400), 400, "VALIDATION_ERROR", "id");
        var unknown = get(LIST + "/" + EXTRA, 404);
        assertProblem(unknown, 404, "NOT_FOUND", null);
        assertThat(unknown.path("fieldErrors").isEmpty()).isTrue();
    }

    @Test
    void unexpectedDatabaseFailureReturnsSanitisedProblemAndTheNextRequestCanRecover() throws Exception {
        var invalidZone = "Internal/Invalid-Test-Time-Zone";
        time(AI, "starts", "DATE", "2026-10-11", null, invalidZone, null);
        try {
            var problem = get(LIST, 500);
            assertProblem(problem, 500, "INTERNAL_ERROR", null);
            assertThat(problem.path("title").stringValue()).isNotBlank();
            assertThat(problem.path("detail").stringValue()).isNotBlank();
            assertThat(problem.path("fieldErrors").isEmpty()).isTrue();
            assertThat(problem.toString()).doesNotContain(
                    invalidZone, "timezone(", "catalog_hackathon", "SQLGrammarException", "PSQLException");
        } finally {
            time(AI, "starts", "MINUTE", "2026-11-12", "10:00", "Asia/Almaty", "2026-11-12T05:00:00Z");
        }
        assertThat(ids(get(LIST, 200))).contains(AI);
    }

    @Test
    void listQueriesStayBoundedWhenThePageGrowsAndTopicsAreFetched() throws Exception {
        for (int index = 1; index <= 25; index++) {
            var id = new UUID(0L, index).toString();
            cloneEvent(AI, id);
            jdbc.update("insert into hackatlas.catalog_hackathon_topic values (?,?)", uuid(id), "fintech");
            jdbc.update("insert into hackatlas.catalog_hackathon_topic values (?,?)", uuid(id), "web");
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        statistics.clear();
        assertThat(ids(list("size", "1"))).hasSize(1);
        long smallPageQueries = statistics.getPrepareStatementCount();
        statistics.clear();
        var large = list("size", "50");
        long largePageQueries = statistics.getPrepareStatementCount();
        assertThat(ids(large)).hasSize(30).doesNotHaveDuplicates();
        assertThat(largePageQueries).isBetween(1L, 4L);
        assertThat(largePageQueries).isLessThanOrEqualTo(smallPageQueries + 1);
        for (var item : large.path("items")) {
            if (item.path("id").stringValue().equals(new UUID(0L, 1).toString())) {
                assertThat(item.path("topics").size()).isEqualTo(3);
            }
        }
    }

    private JsonNode list(String key, String value) throws Exception {
        return get(listPath(Map.of(key, value)), 200);
    }

    private static String listPath(Map<String, String> parameters) {
        return LIST + "?" + parameters.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private JsonNode detail(String id) throws Exception {
        return get(LIST + "/" + id, 200);
    }

    private JsonNode get(String path, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, application/problem+json")
                .GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(expectedStatus);
        var body = json.readTree(response.body());
        var operationPath = path.startsWith("/api/v1/metadata/") ? "/metadata/filters"
                : path.startsWith(LIST + "/") ? "/hackathons/{id}" : "/hackathons";
        var capture = json.createObjectNode().put("operationPath", operationPath)
                .put("requestPath", path).put("status", response.statusCode())
                .put("contentType", response.headers().firstValue("Content-Type").orElse(""));
        capture.set("body", body);
        responses.add(capture);
        if (expectedStatus >= 400) {
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/problem+json");
            var requestId = body.path("requestId").stringValue();
            assertThat(UUID.fromString(requestId)).isNotNull();
            assertThat(response.headers().firstValue("X-Request-Id")).contains(requestId);
        }
        return body;
    }

    private void assertProblem(JsonNode body, int status, String code, String field) {
        assertThat(body.path("status").intValue()).isEqualTo(status);
        assertThat(body.path("code").stringValue()).isEqualTo(code);
        assertThat(body.path("instance").stringValue()).startsWith("/api/v1/hackathons");
        assertThat(body.toString()).doesNotContain(
                "org.hibernate", "java.sql", "jdbc:", "SQLException", "stackTrace", "DROP TABLE", "Authorization");
        if (field != null) {
            assertThat(stringField(body.path("fieldErrors"), "field")).contains(field);
        }
    }

    private JsonNode fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/api/examples/" + name)) {
            assertThat(stream).as("canonical fixture " + name).isNotNull();
            return json.readTree(stream);
        }
    }

    private static List<String> ids(JsonNode page) {
        return stringField(page.path("items"), "id");
    }

    private static List<String> stringField(JsonNode array, String field) {
        var values = new ArrayList<String>();
        for (var item : array) {
            values.add(item.path(field).stringValue());
        }
        return values;
    }

    private void update(String id, String assignments, Object... arguments) {
        var values = new ArrayList<>(List.of(arguments));
        values.add(uuid(id));
        jdbc.update("update hackatlas.catalog_hackathon set " + assignments + " where id = ?", values.toArray());
    }

    private void time(String id, String prefix, String precision, String date, String time, String zone, String instant) {
        jdbc.update("update hackatlas.catalog_hackathon set " + prefix + "_precision = ?, "
                        + prefix + "_date = ?::date, " + prefix + "_time = ?::time, "
                        + prefix + "_time_zone = ?, " + prefix + "_instant = ?::timestamptz where id = ?",
                precision, date, time, zone, instant, uuid(id));
    }

    private void cloneEvent(String source, String destination) {
        var columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'hackatlas' and table_name = 'catalog_hackathon'
                order by ordinal_position
                """, String.class);
        var selected = columns.stream().map(column -> switch (column) {
            case "id", "slug" -> "?";
            default -> column;
        }).collect(Collectors.joining(","));
        jdbc.update("insert into hackatlas.catalog_hackathon (" + String.join(",", columns) + ") select "
                        + selected + " from hackatlas.catalog_hackathon where id = ?",
                uuid(destination), "test-" + destination, uuid(source));
        jdbc.update("""
                insert into hackatlas.catalog_hackathon_topic (hackathon_id,tag_id)
                select ?,tag_id from hackatlas.catalog_hackathon_topic where hackathon_id = ?
                """, uuid(destination), uuid(source));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
