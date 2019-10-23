package deribit.candler;

import com.squareup.moshi.*;

public class Trade {
	public long trade_seq, timestamp;
	public String trade_id, instrument_name, direction;
	public double price, amount;

	/*
		Direction of the "tick" (0 = Plus Tick, 1 = Zero-Plus Tick, 2 = Minus Tick, 3 = Zero-Minus Tick).
	*/
	public int tick_direction;

	public String toString() {
		return new Moshi.Builder().build().adapter(Trade.class).toJson(this);
	}
}
