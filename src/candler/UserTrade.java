package candler;

import com.squareup.moshi.*;

public class UserTrade extends Trade {
	public String	state, order_type, order_id, fee_currency,
					liquidity;// Describes what was role of users order: "M" when it was maker order, "T" when it was taker order
	public double fee;
	public boolean reduce_only, post_only, self_trade;

	public String toString() {
		return new Moshi.Builder().build().adapter(UserTrade.class).toJson(this);
	}
}
