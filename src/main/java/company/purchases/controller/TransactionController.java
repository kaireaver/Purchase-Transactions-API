package company.purchases.controller;

import company.purchases.domain.dto.ConvertedOutputTransactionDTO;
import company.purchases.domain.dto.InputTransactionDTO;
import company.purchases.domain.dto.OutputTransactionDTO;
import company.purchases.service.TransactionService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static company.purchases.util.ErrorConstants.DATABASE_ERROR;
import static company.purchases.util.ErrorConstants.UNEXPECTED_ERROR;

/**
 * Transaction Spring Webflux controller responsible for public-facing operations related to purchase transactions,
 * such as creating a new transaction or fetching transaction data. Resilience4j rate limiter configured for all endpoints.
 */
@RestController
@RequestMapping("/transactions")
@Slf4j
@OpenAPIDefinition(info = @Info(
        title = "Purchase Transactions API",
        version = "1.0",
        license = @License(name = "MIT License", url = "https://www.mit.edu/~amini/LICENSE.md"),
        contact = @Contact(name = "Arthur Raposo")
))
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final RateLimiter rateLimiter;

    /**
     * Create a new transaction.
     *
     * @param inputTransactionDTO the input transaction dto
     * @return the created transaction, with the generated id
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ResponseBody
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Transaction was created",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = OutputTransactionDTO.class))})
    })
    @Operation(summary = "Create a new transaction in the Database")
    public Mono<OutputTransactionDTO> saveTransaction(@Valid @RequestBody InputTransactionDTO inputTransactionDTO) {
        log.info("Received request to create transaction");
        return Mono.just(inputTransactionDTO)
                .flatMap(transactionService::save)
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .doOnSuccess(t -> log.debug("Transaction created successfully"))
                .doOnError(e -> log.error("Error creating transaction: ", e))
                .onErrorResume(DataAccessException.class, e -> Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, DATABASE_ERROR, e)))
                .onErrorResume(e -> Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR, e)));
    }

    /**
     * Gets transactions.
     *
     * @return the transactions
     */
    @Operation(summary = "Get all transactions")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Found purchase records table",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = OutputTransactionDTO.class))})
    })
    @GetMapping
    public Flux<OutputTransactionDTO> getTransactions() {
        log.info("Received request to get all transactions");
        return transactionService.findAll()
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .doOnEach(signal -> {
                    if (signal.hasError()) {
                        log.error("Error getting transactions: ", signal.getThrowable());
                    }
                })
                .onErrorResume(DataAccessException.class, e -> Flux.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, DATABASE_ERROR, e)))
                .onErrorResume(e -> Flux.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR, e)));
    }

    /**
     * Gets transaction by id.
     *
     * @param id the id
     * @return the transaction by id
     */
    @Operation(summary = "Get transaction by Id")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Found record for given Id",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = OutputTransactionDTO.class))}),
            @ApiResponse(responseCode = "404", description = "Record not found for given Id",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = OutputTransactionDTO.class))}),
            @ApiResponse(responseCode = "400", description = "Request was not well-formed or there is no exchange rate data for given currency in the last six months of the purchase date",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = OutputTransactionDTO.class))})
    })
    @GetMapping("/{id}")
    public Mono<ResponseEntity<OutputTransactionDTO>> getTransactionById(@PathVariable Long id) {
        log.info("Received request to get transaction by ID: {}", id);
        return  transactionService.getTransactionById(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .doOnError(e -> {
                    if (e instanceof DataAccessException) {
                        log.error("Database error when getting transaction by ID and currency: ", e);
                    } else {
                        log.error("Unexpected error when getting transaction by ID and currency: ", e);
                    }
                })
                .onErrorResume(DataAccessException.class, e -> Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, DATABASE_ERROR, e)))
                .onErrorResume(e -> Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR, e)));
    }

    /**
     * Gets transaction by id and currency.
     *
     * @param id       the id
     * @param currency the currency
     * @return the transaction by id and currency
     */
    @Operation(summary = "Get transaction by Id and Convert")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Found record and conversion for given Id",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = ConvertedOutputTransactionDTO.class))}),
            @ApiResponse(responseCode = "404", description = "Record not found for given Id",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = ConvertedOutputTransactionDTO.class))}),
            @ApiResponse(responseCode = "400", description = "Request was not well-formed or there is no exchange rate data for given currency in the last six months of the purchase date",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = ConvertedOutputTransactionDTO.class))})
    })
    @GetMapping("/{id}/convert")
    public Mono<ResponseEntity<ConvertedOutputTransactionDTO>> getTransactionByIdAndCurrency(@PathVariable Long id, @RequestParam String currency) {
        log.info("Received request to get transaction by ID: {} and currency: {}", id, currency);
        return  transactionService.getTransactionWithConvertedAmount(id, currency)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .doOnError(e -> {
                    if (e instanceof DataAccessException) {
                        log.error("Database error when getting transaction by ID and currency: ", e);
                    } else {
                        log.error("Unexpected error when getting transaction by ID and currency: ", e);
                    }
                })
                .onErrorResume(DataAccessException.class, e -> Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, DATABASE_ERROR, e)))
                .onErrorResume(e -> Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR, e)));
    }

}
