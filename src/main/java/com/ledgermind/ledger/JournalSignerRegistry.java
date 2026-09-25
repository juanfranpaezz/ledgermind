package com.ledgermind.ledger;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registro de algoritmos de firma del journal: el lado de VERIFICACION de la crypto-agility.
 *
 * <p>Por que existe: un checkpoint guarda el {@code algorithm} con el que fue firmado. Para verificar un
 * checkpoint hay que usar EL MISMO algoritmo, no el que el firmante activo use HOY. Sin este registro el
 * servicio solo podia verificar checkpoints firmados con el unico firmante inyectado: rotar de ML-DSA a
 * Ed25519 dejaba los checkpoints viejos imposibles de verificar (el KeyFactory equivocado tiraba un
 * {@link IllegalStateException} estructural). Esto completa la agility "hacia atras": cada checkpoint se
 * verifica con su propio esquema, soportando >1 algoritmo en paralelo.
 *
 * <p>La VERIFICACION no necesita clave privada: el algoritmo, la clave publica y la firma viajan en el
 * propio checkpoint. Por eso el registro indexa {@link JournalSigner} por {@link JournalSigner#algorithm()}
 * y al verificar SOLO usa su metodo {@code verify} — un firmante sin material privado (montado solo para
 * verificar un esquema retirado) encajaria igual si implementara la interfaz.
 *
 * <p>Algoritmo DESCONOCIDO (no registrado): NO es evidencia de tamper. Es una falla estructural —
 * el verificador para ese esquema no esta desplegado— y debe fallar RUIDOSO ({@link IllegalStateException}),
 * coherente con la disciplina de {@code MlDsaJournalSigner.verify} (no disfrazar una causa ambiental de
 * "MANIPULACION DETECTADA"). Un veredicto de seguridad falso es peor que un error visible.
 */
@Component
public class JournalSignerRegistry {

    private final Map<String, JournalSigner> byAlgorithm;

    /**
     * Spring inyecta TODOS los beans {@link JournalSigner} del contexto. Se indexan por su
     * {@link JournalSigner#algorithm()}; dos firmantes con el mismo nombre de algoritmo es un error de
     * configuracion y falla al arrancar (no se elige uno en silencio).
     */
    public JournalSignerRegistry(List<JournalSigner> signers) {
        this.byAlgorithm = signers.stream().collect(Collectors.toMap(
                JournalSigner::algorithm,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                            "Dos JournalSigner declaran el mismo algorithm() = '" + a.algorithm()
                                    + "': " + a.getClass().getName() + " y " + b.getClass().getName());
                }));
    }

    /**
     * Verifica una firma despachando por el NOMBRE de algoritmo que el checkpoint registro.
     *
     * @param algorithm        nombre del algoritmo con el que se firmo (campo del checkpoint)
     * @param data             bytes canonicos que se firmaron
     * @param signatureBase64  firma en base64
     * @param publicKeyBase64  clave publica (base64, X.509) que acompaña al checkpoint
     * @return {@code true} si la firma cierra; {@code false} SOLO si la firma no verifica (tamper genuino)
     * @throws IllegalStateException si el algoritmo no esta registrado (falla estructural, NO tamper)
     */
    public boolean verify(String algorithm, byte[] data, String signatureBase64, String publicKeyBase64) {
        JournalSigner verifier = byAlgorithm.get(algorithm);
        if (verifier == null) {
            // Algoritmo no soportado: NO podemos verificar. NO es evidencia criptografica de tamper ->
            // fallamos ruidoso en vez de devolver un falso veredicto de seguridad.
            throw new IllegalStateException("Algoritmo de firma desconocido/no registrado: '" + algorithm
                    + "'. Algoritmos soportados: " + supportedAlgorithms()
                    + ". (No se pudo verificar el checkpoint; esto NO es evidencia de manipulacion.)");
        }
        return verifier.verify(data, signatureBase64, publicKeyBase64);
    }

    /** Algoritmos que este despliegue puede verificar (para diagnostico/observabilidad). */
    public List<String> supportedAlgorithms() {
        return byAlgorithm.keySet().stream().sorted().toList();
    }

    /** {@code true} si el despliegue tiene un verificador para ese algoritmo. */
    public boolean supports(String algorithm) {
        return byAlgorithm.containsKey(algorithm);
    }

    /**
     * El firmante ACTIVO (el que firma los checkpoints NUEVOS), resuelto por nombre de algoritmo.
     * Es el lado de FIRMA de la agility: rotar de esquema = cambiar este nombre por configuracion,
     * sin tocar el dominio. Verificar sigue funcionando para TODOS los esquemas registrados, asi los
     * checkpoints firmados con el esquema anterior se siguen auditando tras la rotacion.
     *
     * @throws IllegalStateException si el algoritmo activo configurado no tiene un firmante registrado
     *                               (falla RUIDOSO en el primer intento de firmar — el primer tick del
     *                               checkpoint — no firma en silencio con el esquema equivocado)
     */
    public JournalSigner activeSigner(String algorithm) {
        JournalSigner signer = byAlgorithm.get(algorithm);
        if (signer == null) {
            throw new IllegalStateException("Algoritmo de firma ACTIVO no soportado: '" + algorithm
                    + "'. Algoritmos disponibles: " + supportedAlgorithms()
                    + ". Revisa la propiedad ledgermind.journal.signer.algorithm.");
        }
        return signer;
    }
}
