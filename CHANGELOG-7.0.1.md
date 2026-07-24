# VoidEconomy 7.0.1

- Reduced all 13 default `/sell` base and minimum prices to the requested values.
- Preserved reference quantities, drop rates, recovery half-lives, weighted-sale
  calculations, zero-demand fish handling, and all other floating-market behavior.
- Increased successful Auction House and Buy Order transaction fees from 2% to 3.5%.
- Existing 7.0.0 default markets migrate proportionally without resetting market
  pressure: current, lowest, and highest prices retain their percentage of base.
- Historical generated-currency and fee statistics remain unchanged.
- Existing configurations using the old 2% fee migrate automatically to 3.5%.
