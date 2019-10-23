package deribit.candler;

import com.squareup.moshi.Moshi;

/*
	"time_in_force":			"good_til_cancelled",
	"replaced":					false,
	"reduce_only":				true,
	"profit_loss":				0.0,
	"price":					8003.5,
	"post_only":				true,
	"order_type":				"limit",
	"order_state":				"filled",
	"order_id":					"297",
	"max_show":					10,
	"last_update_timestamp":	15712,
	"label":					"",
	"is_liquidation":			false,
	"instrument_name":			"BTC-PERPETUAL",
	"filled_amount":			10,
	"direction":				"sell",
	"creation_timestamp":		1571,
	"commission":				-3.1e-7,
	"average_price":			8003.5,
	"api":						false,
	"amount":					10
*/
public class Order {
	public String	time_in_force,
					label,

					/*
						"open",
						"filled",
						"rejected",
						"cancelled",
						"untriggered"
					 */
					order_state,

					order_id,
					instrument_name,
					direction;
	public double price, commission;
	public boolean post_only, reduce_only, api, is_liquidation;
	public long amount, creation_timestamp, filled_amount, last_update_timestamp;

	public String toString() {
		return new Moshi.Builder().build().adapter(Order.class).toJson(this);
	}
}
