package deribit.utils;

import java.math.*;
import java.nio.charset.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.apache.commons.codec.binary.*;

public class Utils {
	public static String encodeHMACSHA256(String key, String data) throws Exception {
		final Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
		sha256_HMAC.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

		return Hex.encodeHexString(sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8)));
	}

	public static double round(double value, int places) {
		if(places < 0) throw new IllegalArgumentException();

		return (new BigDecimal(value)).setScale(places, RoundingMode.HALF_UP).doubleValue();
	}
}
