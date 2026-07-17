import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class DecryptDiagnostics {
    private static final String PREFIX = "AUTO_ACCOUNTING_DIAGNOSTICS_V1:";
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KDF_ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;

    private DecryptDiagnostics() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            fail("Usage: decrypt-diagnostics.ps1 -InputPath <file> -OutputPath <jsonl>");
        }
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output)) {
            fail("Output already exists.");
        }

        char[] passphrase = null;
        byte[] keyBytes = null;
        byte[] plainText = null;
        try {
            passphrase = readPassphrase();
            if (passphrase.length < 8) {
                fail("Passphrase must contain at least 8 characters.");
            }
            String exportText = Files.readString(input, StandardCharsets.UTF_8).strip();
            if (!exportText.startsWith(PREFIX)) {
                fail("Unsupported diagnostic export format.");
            }
            byte[] payload = Base64.getDecoder().decode(exportText.substring(PREFIX.length()));
            if (payload.length <= SALT_BYTES + IV_BYTES + TAG_BITS / 8) {
                fail("Invalid diagnostic export payload.");
            }
            byte[] salt = Arrays.copyOfRange(payload, 0, SALT_BYTES);
            byte[] iv = Arrays.copyOfRange(payload, SALT_BYTES, SALT_BYTES + IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, SALT_BYTES + IV_BYTES, payload.length);

            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_BITS);
            try {
                keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            } finally {
                spec.clearPassword();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(TAG_BITS, iv)
            );
            plainText = cipher.doFinal(encrypted);

            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(output, plainText, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("Decrypted JSONL written to: " + output);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            fail("Decryption failed. Check the file and passphrase.");
        } catch (IOException error) {
            fail("Unable to read or write the requested path.");
        } finally {
            if (passphrase != null) Arrays.fill(passphrase, '\0');
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
            if (plainText != null) Arrays.fill(plainText, (byte) 0);
        }
    }

    private static char[] readPassphrase() throws IOException {
        InputStreamReader reader = new InputStreamReader(System.in, StandardCharsets.US_ASCII);
        CharArrayWriter writer = new CharArrayWriter();
        int value;
        while ((value = reader.read()) != -1 && value != '\n' && value != '\r') {
            writer.write(value);
        }
        char[] encodedChars = writer.toCharArray();
        byte[] encodedBytes = new byte[encodedChars.length];
        byte[] passphraseBytes = null;
        try {
            for (int index = 0; index < encodedChars.length; index++) {
                if (encodedChars[index] > 0x7F) {
                    throw new IllegalArgumentException("Invalid passphrase encoding.");
                }
                encodedBytes[index] = (byte) encodedChars[index];
            }
            passphraseBytes = Base64.getDecoder().decode(encodedBytes);
            CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(passphraseBytes));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            return result;
        } finally {
            writer.reset();
            Arrays.fill(encodedChars, '\0');
            Arrays.fill(encodedBytes, (byte) 0);
            if (passphraseBytes != null) Arrays.fill(passphraseBytes, (byte) 0);
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
