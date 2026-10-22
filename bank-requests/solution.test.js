const test = require('node:test');
const assert = require('node:assert/strict');
const { solution } = require('./solution');

test('example 1 applies every valid request', () => {
  const balances = [10, 100, 20, 50, 30];
  const requests = [
    'withdraw 2 10',
    'transfer 5 1 20',
    'deposit 5 20',
    'transfer 3 4 15',
  ];

  assert.deepEqual(solution(balances, requests), [30, 90, 5, 65, 30]);
  assert.deepEqual(balances, [10, 100, 20, 50, 30]);
});

test('example 2 stops at the first invalid transfer', () => {
  const balances = [20, 1000, 500, 40, 90];
  const requests = ['deposit 3 400', 'transfer 1 2 30', 'withdraw 4 50'];

  assert.deepEqual(solution(balances, requests), [-2]);
  assert.deepEqual(balances, [20, 1000, 500, 40, 90]);
});

test('an out-of-range account invalidates the whole request', () => {
  const balances = [10, 20];

  assert.deepEqual(solution(balances, ['withdraw 3 1']), [-1]);
  assert.deepEqual(solution(balances, ['transfer 1 1000 5']), [-1]);
  assert.deepEqual(solution(balances, ['transfer 1000 1 5']), [-1]);
  assert.deepEqual(balances, [10, 20]);
});

test('a withdraw that exceeds the balance is invalid', () => {
  const balances = [10, 20];

  assert.deepEqual(solution(balances, ['withdraw 1 11']), [-1]);
  assert.deepEqual(balances, [10, 20]);
});

test('a deposit to an invalid account is invalid', () => {
  const balances = [10];

  assert.deepEqual(solution(balances, ['deposit 2 5']), [-1]);
  assert.deepEqual(balances, [10]);
});

test('a same-account transfer is a no-op when the balance covers the sum', () => {
  const balances = [40, 10];

  assert.deepEqual(solution(balances, ['transfer 1 1 40']), [40, 10]);
  assert.deepEqual(balances, [40, 10]);
});

test('a same-account transfer fails when the sum exceeds the balance', () => {
  const balances = [40, 10];

  assert.deepEqual(solution(balances, ['transfer 2 2 11']), [-1]);
  assert.deepEqual(balances, [40, 10]);
});

test('a sum of 0 is valid for deposit, withdraw, and transfer', () => {
  const balances = [5, 7];

  assert.deepEqual(
    solution(balances, ['deposit 1 0', 'withdraw 2 0', 'transfer 1 2 0', 'transfer 1 1 0']),
    [5, 7],
  );
  assert.deepEqual(balances, [5, 7]);
});

test('an invalid first request returns -1 and ignores later requests', () => {
  const balances = [100];

  assert.deepEqual(solution(balances, ['withdraw 1 101', 'deposit 1 50']), [-1]);
  assert.deepEqual(balances, [100]);
});
