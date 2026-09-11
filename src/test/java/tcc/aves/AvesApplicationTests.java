package tcc.aves;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code jwt.secret} não tem default versionado em {@code application.properties}
 * (é obrigatório vir de variável de ambiente em produção), então o teste
 * precisa fornecer um valor próprio só para conseguir subir o contexto.
 */
@SpringBootTest
@TestPropertySource(properties = "jwt.secret=test-only-secret-key-not-for-production-use-1234567890")
class AvesApplicationTests {

	@Test
	void contextLoads() {
	}

}
