package deribit.rest;

import com.squareup.moshi.Moshi;

/*
	"updated_timestamp":	1571,
	"type":					"subaccount"
	"state":				"confirmed",
	"other_side":			"pr....",
	"id":					80,
	"direction":			"payment",
	"currency":				"BTC",
	"created_timestamp":	15717,
	"amount":				0.00003
 */
public class Transfer {
	public long id, created_timestamp;
	public String other_side, direction, type, state;
	public double amount;

	public String toString() {
		return new Moshi.Builder().build().adapter(Transfer.class).toJson(this);
	}
}
