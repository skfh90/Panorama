/**
 * Apply deposit, withdraw, and transfer requests in order.
 *
 * Balances are copied so the caller's array stays unchanged and a rejected
 * request is never returned as a half-applied result. Request account numbers
 * are 1-based: `balances[0]` is account 1.
 *
 * Account range is checked before funds. An account outside `1..balances.length`
 * is invalid even when the named source would have covered `sum`, and a
 * transfer must not update only one of its two accounts. A withdraw or
 * transfer that asks for more than the source currently holds is also invalid.
 * A deposit never fails for insufficient funds.
 *
 * Processing stops at the first invalid request. Its 1-based index is returned
 * as `[-requestId]`, and every later request is ignored. If `i === j` on a
 * transfer, the account is valid, and its balance covers `sum`, nothing moves:
 * the same money would leave and return. That transfer is still invalid when
 * `sum` exceeds the balance.
 *
 * @param {number[]} balances Money in each account. Index 0 is account 1.
 * @param {string[]} requests Requests in the order they should be applied.
 * @returns {number[]} Final balances, or [-requestId] for the first invalid request.
 */
function solution(balances, requests) {
  const accounts = balances.slice();
  const count = accounts.length;

  for (let index = 0; index < requests.length; index++) {
    const parts = requests[index].split(' ');
    const kind = parts[0];
    const requestId = index + 1;

    if (kind === 'deposit') {
      const account = Number(parts[1]);
      const sum = Number(parts[2]);
      if (!isValidAccount(account, count)) {
        return [-requestId];
      }
      accounts[account - 1] += sum;
    } else if (kind === 'withdraw') {
      const account = Number(parts[1]);
      const sum = Number(parts[2]);
      if (!isValidAccount(account, count)) {
        return [-requestId];
      }
      if (accounts[account - 1] < sum) {
        return [-requestId];
      }
      accounts[account - 1] -= sum;
    } else {
      const from = Number(parts[1]);
      const to = Number(parts[2]);
      const sum = Number(parts[3]);
      if (!isValidAccount(from, count) || !isValidAccount(to, count)) {
        return [-requestId];
      }
      if (accounts[from - 1] < sum) {
        return [-requestId];
      }
      // Same-account transfer: the funds check above already passed, so skip the move.
      if (from !== to) {
        accounts[from - 1] -= sum;
        accounts[to - 1] += sum;
      }
    }
  }

  return accounts;
}

function isValidAccount(account, count) {
  return Number.isInteger(account) && account >= 1 && account <= count;
}

module.exports = { solution };
