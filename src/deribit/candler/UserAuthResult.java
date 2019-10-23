package deribit.candler;

import com.squareup.moshi.Moshi;

public class UserAuthResult {
	public String token_type, scope, refresh_token, access_token;
	public long expires_in;

	public String toString() {
		return new Moshi.Builder().build().adapter(UserAuthResult.class).toJson(this);
	}
}
