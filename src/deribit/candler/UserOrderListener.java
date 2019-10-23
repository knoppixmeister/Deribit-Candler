package deribit.candler;

public interface UserOrderListener {
	public void onNewUserOrder(Order order,	final String instrument, final String rawData);
}
