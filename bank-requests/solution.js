/**
 * Apply deposit, withdraw, and transfer requests to a copy of account balances.
 * Accounts are 1-based. Processing stops at the first invalid request and
 * returns [-requestId]. A fully valid sequence returns the resulting balances.
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
