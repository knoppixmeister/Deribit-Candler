package deribit.candler;

import com.squareup.moshi.*;

public class Order {
	public String time_in_force, label, order_state, order_id, instrument_name, direction;
	public double price, commission;
	public boolean post_only, api, is_liquidation;
	public long amount, creation_timestamp, filled_amount, last_update_timestamp;

	public String toString() {
		return new Moshi.Builder().build().adapter(Order.class).toJson(this);
	}
}
