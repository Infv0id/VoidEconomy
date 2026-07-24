# VoidEconomy 7.0.0

## Commands and permissions

- Added `/sell`, `/sell hand`, `/sell all`, `/sell price [material]`, and `/sell prices`.
- Added `/ve help`, `/ve admin stats [today|week|all]`, and `/ve admin sell ...`.
- Added `voideconomy.sell`, `.sell.hand`, `.sell.all`, `.sell.prices`,
  `voideconomy.admin.sell`, and `voideconomy.admin.statistics`.

## Floating sell market

- Added a protected, paginated server-market GUI with eight sorting modes.
- Added quantity selection and a final weighted-price confirmation screen.
- Prices fall progressively using
  `current × (1 − drop rate)^(quantity/reference quantity)`.
- Prices recover toward base price using configurable half-life recovery.
- Fish may reach zero demand; zero-value sales leave items untouched.
- Only exact, plain configured items are accepted. Named, custom-data, plugin,
  decorative, cooked-fish, and unsupported items are rejected.
- Added the 13 requested default materials and recommended settings.
- Complete sale records include player, material, quantity, starting price,
  ending price, average price, generated amount, and timestamp.

## Marketplace fees

- Successful Auction House sales now destroy a configurable 2% fee.
- Successful Buy Order deliveries destroy a configurable 2% fee per fill.
- Buyers and escrow still pay the advertised gross amount.
- Sellers receive the net amount and see gross, fee, and net values.
- Listing, posting, cancellation, expiry, refunds, failed operations, item
  returns, administrative cancellation, and `/pay` remain fee-free.

## Statistics and administration

- Added `/admin` Economy Statistics with circulating balances, Buy Order
  escrow, physical bank-note value, generated money, destroyed money, net
  change, fee breakdowns, and hourly supply snapshots.
- Added configurable inflation-risk thresholds and 24-hour/7-day growth.
- Added sell-market inspection, enable/disable, add/remove, price/base/minimum,
  drop rate, reference quantity, recovery, reset, and freeze commands.
- Administrative balance increases and removals are included in generation and
  destruction statistics.

## Persistence and compatibility

- Existing balances, auctions, orders, bounties, notes, transactions, and stash
  records are unchanged.
- New market state is stored separately in `sell-market.yml`.
- Market saves use temporary-file replacement and retain bounded detailed
  history, fee totals, sale records, and supply snapshots.
- Old sold auctions remain collectible without retroactive fees.

## Safety

- Sell commits are synchronized and revalidate the exact inventory immediately
  before item removal.
- GUI shift-click, drag, number-key, double-click, offhand, and decorative-item
  extraction paths are cancelled by the protected menu.
- Confirmations are recalculated against the current server-wide market state.
