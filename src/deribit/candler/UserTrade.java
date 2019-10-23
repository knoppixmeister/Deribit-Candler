package deribit.candler;

import com.squareup.moshi.Moshi;

/*
{
	"state":			"filled",
	"self_trade":		false,
	"reduce_only":		true,
	"post_only":		true,
	"order_type":		"limit",
	"order_id":			"2969",
	"liquidity":		"M",
	"index_price":		8134.66,
	"fee_currency":		"BTC",
	"fee":				-3e-7,
}
*/
public class UserTrade extends Trade {
	public String	state, order_type, order_id, fee_currency,
					liquidity;// Describes what was role of users order: "M" when it was maker order, "T" when it was taker order
	public double fee;
	public boolean reduce_only, post_only, self_trade;

	public String toString() {
		return new Moshi.Builder().build().adapter(UserTrade.class).toJson(this);
	}
}
