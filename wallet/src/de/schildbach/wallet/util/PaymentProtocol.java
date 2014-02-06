/*
 * Copyright 2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.util;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.protocols.payments.PaymentProtocol.PkiVerificationData;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.protocols.payments.PaymentSession;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UninitializedMessageException;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.data.PaymentIntent;

/**
 * @author Andreas Schildbach
 */
public final class PaymentProtocol
{
	public static final String MIMETYPE_PAYMENTREQUEST = "application/bitcoin-paymentrequest"; // BIP 71
	public static final String MIMETYPE_PAYMENT = "application/bitcoin-payment"; // BIP 71
	public static final String MIMETYPE_PAYMENTACK = "application/bitcoin-paymentack"; // BIP 71

	public static Protos.PaymentRequest createPaymentRequest(final Coin amount, @Nonnull final Address toAddress, final String memo,
			final String paymentUrl)
	{
		final Protos.Output.Builder output = Protos.Output.newBuilder();
		output.setAmount(amount != null ? amount.longValue() : 0);
		output.setScript(ByteString.copyFrom(ScriptBuilder.createOutputScript(toAddress).getProgram()));

		final Protos.PaymentDetails.Builder paymentDetails = Protos.PaymentDetails.newBuilder();
		paymentDetails.setNetwork(Constants.NETWORK_PARAMETERS.getPaymentProtocolId());
		paymentDetails.addOutputs(output);
		if (memo != null)
			paymentDetails.setMemo(memo);
		if (paymentUrl != null)
			paymentDetails.setPaymentUrl(paymentUrl);
		paymentDetails.setTime(System.currentTimeMillis());

		final Protos.PaymentRequest.Builder paymentRequest = Protos.PaymentRequest.newBuilder();
		paymentRequest.setSerializedPaymentDetails(paymentDetails.build().toByteString());

		return paymentRequest.build();
	}

	public static PaymentIntent parsePaymentRequest(@Nonnull final byte[] serializedPaymentRequest) throws PaymentProtocolException
	{
		try
		{
			if (serializedPaymentRequest.length > 50000)
				throw new PaymentProtocolException("payment request too big: " + serializedPaymentRequest.length);

			final Protos.PaymentRequest paymentRequest = Protos.PaymentRequest.parseFrom(serializedPaymentRequest);

			final String pkiName;
			final String pkiOrgName;
			final String pkiCaName;
			if (!"none".equals(paymentRequest.getPkiType()))
			{
				// implicitly verify PKI signature
				final PkiVerificationData verificationData = new PaymentSession(paymentRequest, true).pkiVerificationData;
				pkiName = verificationData.displayName;
				pkiOrgName = null;
				pkiCaName = verificationData.rootAuthorityName;
			}
			else
			{
				pkiName = null;
				pkiOrgName = null;
				pkiCaName = null;
			}

			if (paymentRequest.getPaymentDetailsVersion() != 1)
				throw new PaymentProtocolException.InvalidVersion("cannot handle payment details version: "
						+ paymentRequest.getPaymentDetailsVersion());

			final Protos.PaymentDetails paymentDetails = Protos.PaymentDetails.newBuilder().mergeFrom(paymentRequest.getSerializedPaymentDetails())
					.build();

			final long currentTimeSecs = System.currentTimeMillis() / 1000;
			if (paymentDetails.hasExpires() && currentTimeSecs >= paymentDetails.getExpires())
				throw new PaymentProtocolException.Expired("payment details expired: current time " + currentTimeSecs + " after expiry time "
						+ paymentDetails.getExpires());

			if (!paymentDetails.getNetwork().equals(Constants.NETWORK_PARAMETERS.getPaymentProtocolId()))
				throw new PaymentProtocolException.InvalidNetwork("cannot handle payment request network: " + paymentDetails.getNetwork());

			final ArrayList<PaymentIntent.Output> outputs = new ArrayList<PaymentIntent.Output>(paymentDetails.getOutputsCount());
			for (final Protos.Output output : paymentDetails.getOutputsList())
				outputs.add(parseOutput(output));

			final String memo = paymentDetails.hasMemo() ? paymentDetails.getMemo() : null;
			final String paymentUrl = paymentDetails.hasPaymentUrl() ? paymentDetails.getPaymentUrl() : null;
			final byte[] merchantData = paymentDetails.hasMerchantData() ? paymentDetails.getMerchantData().toByteArray() : null;

			final byte[] paymentRequestHash = Hashing.sha256().hashBytes(serializedPaymentRequest).asBytes();

			final PaymentIntent paymentIntent = new PaymentIntent(PaymentIntent.Standard.BIP70, pkiName, pkiOrgName, pkiCaName,
					outputs.toArray(new PaymentIntent.Output[0]), memo, paymentUrl, merchantData, null, paymentRequestHash);

			if (paymentIntent.hasPaymentUrl() && !paymentIntent.isSupportedPaymentUrl())
				throw new PaymentProtocolException.InvalidPaymentURL("cannot handle payment url: " + paymentIntent.paymentUrl);

			return paymentIntent;
		}
		catch (final InvalidProtocolBufferException x)
		{
			throw new PaymentProtocolException(x);
		}
		catch (final UninitializedMessageException x)
		{
			throw new PaymentProtocolException(x);
		}
	}

	private static PaymentIntent.Output parseOutput(@Nonnull final Protos.Output output) throws PaymentProtocolException.InvalidOutputs
	{
		try
		{
			final Coin amount = Coin.valueOf(output.getAmount());
			final Script script = new Script(output.getScript().toByteArray());
			return new PaymentIntent.Output(amount, script);
		}
		catch (final ScriptException x)
		{
			throw new PaymentProtocolException.InvalidOutputs("unparseable script in output: " + output.toString());
		}
	}

	public static Protos.Payment createPaymentMessage(@Nonnull final Transaction transaction, @Nullable final Address refundAddress,
			@Nullable final Coin refundAmount, @Nullable final String memo, @Nullable final byte[] merchantData)
	{
		final Protos.Payment.Builder builder = Protos.Payment.newBuilder();

		builder.addTransactions(ByteString.copyFrom(transaction.unsafeBitcoinSerialize()));

		if (refundAddress != null)
		{
			final Protos.Output.Builder refundOutput = Protos.Output.newBuilder();
			refundOutput.setAmount(refundAmount.longValue());
			refundOutput.setScript(ByteString.copyFrom(ScriptBuilder.createOutputScript(refundAddress).getProgram()));
			builder.addRefundTo(refundOutput);
		}

		if (memo != null)
			builder.setMemo(memo);

		if (merchantData != null)
			builder.setMerchantData(ByteString.copyFrom(merchantData));

		return builder.build();
	}

	public static List<Transaction> parsePaymentMessage(final Protos.Payment paymentMessage)
	{
		final List<Transaction> transactions = new ArrayList<Transaction>(paymentMessage.getTransactionsCount());

		for (final ByteString transaction : paymentMessage.getTransactionsList())
			transactions.add(new Transaction(Constants.NETWORK_PARAMETERS, transaction.toByteArray()));

		return transactions;
	}

	public static Protos.PaymentACK createPaymentAck(@Nonnull final Protos.Payment paymentMessage, @Nullable final String memo)
	{
		final Protos.PaymentACK.Builder builder = Protos.PaymentACK.newBuilder();

		builder.setPayment(paymentMessage);

		builder.setMemo(memo);

		return builder.build();
	}

	public static String parsePaymentAck(@Nonnull final Protos.PaymentACK paymentAck)
	{
		final String memo = paymentAck.hasMemo() ? paymentAck.getMemo() : null;

		return memo;
	}
}
