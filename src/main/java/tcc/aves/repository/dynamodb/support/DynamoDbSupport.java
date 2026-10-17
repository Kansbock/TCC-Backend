package tcc.aves.repository.dynamodb.support;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import tcc.aves.exception.ConcurrentUpdateException;
import tcc.aves.exception.ServiceUnavailableException;

import java.util.List;

/**
 * Traduz falhas do AWS SDK/DynamoDB em exceções de domínio, para que nenhuma
 * exceção específica da AWS escape dos adapters até o controller.
 *
 * <p>{@link TransactionCanceledException} precisa de interpretação
 * específica de cada chamador (qual item da transação falhou define se o
 * problema é duplicidade, conflito de versão ou espécie inexistente), então
 * esta classe só oferece o utilitário genérico para inspecionar os motivos
 * de cancelamento — a decisão de qual exceção de domínio lançar continua no
 * adapter que conhece o significado de cada posição da transação.
 */
public final class DynamoDbSupport {

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private DynamoDbSupport() {
    }

    /** Verdadeiro se a falha de transação na posição indicada foi uma condição não satisfeita. */
    public static boolean isConditionFailure(List<CancellationReason> reasons, int index) {
        if (index >= reasons.size()) {
            return false;
        }
        return CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }

    public static boolean hasConditionFailure(List<CancellationReason> reasons) {
        return reasons.stream().anyMatch(reason -> CONDITIONAL_CHECK_FAILED.equals(reason.code()));
    }

    /**
     * Traduz falhas genéricas (não condicionais) do DynamoDB — throttling,
     * indisponibilidade, erros de conectividade — em
     * {@link ServiceUnavailableException}. Um {@link ConditionalCheckFailedException}
     * isolado (fora de transação) vira {@link ConcurrentUpdateException}.
     * Retorna {@code null} quando a exceção não é reconhecida, para o
     * chamador decidir o que fazer (normalmente relançar).
     */
    public static RuntimeException translateGeneric(Exception e) {
        if (e instanceof ConditionalCheckFailedException) {
            return new ConcurrentUpdateException(
                    "O registro foi alterado por outra operação. Tente novamente.");
        }
        if (e instanceof ProvisionedThroughputExceededException || e instanceof RequestLimitExceededException) {
            return new ServiceUnavailableException(
                    "DynamoDB está com throttling no momento, tente novamente.", e);
        }
        if (e instanceof SdkClientException) {
            return new ServiceUnavailableException("Não foi possível conectar ao DynamoDB.", e);
        }
        if (e instanceof DynamoDbException) {
            return new ServiceUnavailableException("Falha ao acessar o DynamoDB.", e);
        }
        return null;
    }
}
