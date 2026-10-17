#!/bin/bash
# Builds the demo repository for the Marketplace screenshots: a small TypeScript storefront with a
# five-layer stack made by the real gh-stack (no network: init and add are local), and an `origin`
# that looks like github.com/acme/storefront but is a bare repository next to it, reached through
# core.sshCommand. Nothing here talks to GitHub.
#
#   docs/marketplace/tools/demo-repo.sh            # -> build/marketplace/storefront
set -euo pipefail

TOOLS=$(cd "$(dirname "$0")" && pwd)
W=$(cd "$TOOLS/../../.." && pwd)/build/marketplace
REPO=$W/storefront
BARE=$W/origin/storefront.git
REAL_GH=${REAL_GH:-/opt/homebrew/bin/gh}

rm -rf "$REPO" "$BARE" "$W/state"
mkdir -p "$REPO" "$W/origin" "$W/state" "$W/bin"

# --- the fake ssh: `git@github.com:acme/storefront.git` is the bare repository above
cat > "$W/bin/ssh" <<EOF
#!/bin/sh
for last; do :; done
cmd=\$(printf '%s' "\$last" | sed -e "s#'/*acme/storefront.git'#'$BARE'#" -e 's#^git-#git #')
exec sh -c "\$cmd"
EOF
chmod +x "$W/bin/ssh"

export GIT_AUTHOR_NAME="Sam Rivera" GIT_AUTHOR_EMAIL="sam@storefront.dev"
export GIT_COMMITTER_NAME="Sam Rivera" GIT_COMMITTER_EMAIL="sam@storefront.dev"
export GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1 GH_NO_EXTENSION_UPDATE_NOTIFIER=1

day=0
commit() { # commit "message"
  day=$((day + 1))
  local when="2026-10-$(printf '%02d' $((5 + day)))T1$((day % 8)):2$((day % 6)):00+02:00"
  git add -A
  GIT_AUTHOR_DATE=$when GIT_COMMITTER_DATE=$when git commit -q -m "$1"
}
put() { mkdir -p "$(dirname "$1")"; cat > "$1"; }

git init -q --bare -b main "$BARE"
cd "$REPO"
git init -q -b main
git config core.sshCommand "$W/bin/ssh"
git config rerere.enabled true
git config rerere.autoupdate true
git remote add origin git@github.com:acme/storefront.git
printf '.idea/\nnode_modules/\ndist/\n' > .gitignore

# ------------------------------------------------------------------ main
put README.md <<'EOF'
# storefront

Web storefront: catalog, cart, checkout and order history.
EOF
put package.json <<'EOF'
{
  "name": "storefront",
  "private": true,
  "type": "module",
  "scripts": {
    "build": "tsc -p .",
    "test": "vitest run"
  },
  "devDependencies": {
    "typescript": "^5.9.0",
    "vitest": "^3.2.0"
  }
}
EOF
put tsconfig.json <<'EOF'
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "outDir": "dist"
  },
  "include": ["src"]
}
EOF
put src/checkout/payment-service.ts <<'EOF'
export type Currency = "EUR" | "USD" | "GBP";

export interface Money {
  amount: number;
  currency: Currency;
}

export interface PaymentGateway {
  charge(orderId: string, total: Money, idempotencyKey: string): Promise<string>;
  refund(chargeId: string, amount: Money): Promise<void>;
}

export interface Order {
  id: string;
  total: Money;
  chargeId?: string;
}

export class PaymentError extends Error {
  constructor(readonly orderId: string, readonly reason: string) {
    super(`Payment for ${orderId} failed: ${reason}`);
  }
}

export class PaymentService {
  constructor(private readonly gateway: PaymentGateway) {}

  async pay(order: Order): Promise<Order> {
    if (order.chargeId) {
      return order;
    }
    const key = `order-${order.id}`;
    try {
      const chargeId = await this.gateway.charge(order.id, order.total, key);
      return { ...order, chargeId };
    } catch (error) {
      throw new PaymentError(order.id, String(error));
    }
  }

  async refundPartially(order: Order, amount: Money): Promise<void> {
    if (!order.chargeId) {
      throw new PaymentError(order.id, "nothing to refund");
    }
    if (amount.currency !== order.total.currency) {
      throw new PaymentError(order.id, "currency mismatch");
    }
    await this.gateway.refund(order.chargeId, amount);
  }
}
EOF
put src/orders/orders-page.ts <<'EOF'
export interface OrderSummary {
  id: string;
  placedAt: Date;
  total: string;
  status: "paid" | "shipped" | "delivered" | "refunded";
}

export function renderOrders(orders: OrderSummary[]): string {
  if (orders.length === 0) {
    return `<p class="muted">No orders yet.</p>`;
  }
  const rows = orders
    .map((order) => `<tr><td>${order.id}</td><td>${order.placedAt.toLocaleDateString()}</td><td>${order.total}</td><td>${order.status}</td></tr>`)
    .join("");
  return `<table class="orders">${rows}</table>`;
}
EOF
put src/cart/cart.ts <<'EOF'
export interface CartLine {
  sku: string;
  quantity: number;
  unitPrice: number;
}

export class Cart {
  private readonly lines = new Map<string, CartLine>();

  add(sku: string, unitPrice: number, quantity = 1): void {
    const line = this.lines.get(sku);
    if (line) {
      line.quantity += quantity;
    } else {
      this.lines.set(sku, { sku, quantity, unitPrice });
    }
  }

  remove(sku: string): void {
    this.lines.delete(sku);
  }

  total(): number {
    let sum = 0;
    for (const line of this.lines.values()) {
      sum += line.quantity * line.unitPrice;
    }
    return sum;
  }
}
EOF
commit "Initial storefront"

put src/api/router.ts <<'EOF'
export interface Request {
  params: Record<string, string>;
  body: Record<string, unknown>;
}

export interface Response {
  status(code: number): Response;
  json(body: unknown): Response;
}

export type Handler = (request: Request, response: Response) => Promise<Response | void>;

export interface Router {
  get(path: string, handler: Handler): void;
  post(path: string, handler: Handler): void;
}
EOF
put src/orders/order-repository.ts <<'EOF'
import { Order } from "../checkout/payment-service";

export interface OrderRepository {
  find(id: string): Promise<Order | undefined>;
  save(order: Order): Promise<void>;
}
EOF
commit "Add the API router and the order repository"
git push -q origin main
"$REAL_GH" stack --version >/dev/null

# ------------------------------------------------------------------ layer 1: Money type
"$REAL_GH" stack init --base main refunds/money-type >/dev/null
put src/shared/money.ts <<'EOF'
export type Currency = "EUR" | "USD" | "GBP";

const DECIMALS: Record<Currency, number> = { EUR: 2, USD: 2, GBP: 2 };

/** An amount in minor units (cents), so sums never pick up floating-point dust. */
export class Money {
  private constructor(readonly minor: number, readonly currency: Currency) {
    if (!Number.isInteger(minor)) {
      throw new RangeError(`Money needs whole minor units, got ${minor}`);
    }
  }

  static of(amount: number, currency: Currency): Money {
    return new Money(Math.round(amount * 10 ** DECIMALS[currency]), currency);
  }

  static zero(currency: Currency): Money {
    return new Money(0, currency);
  }

  plus(other: Money): Money {
    this.assertSameCurrency(other);
    return new Money(this.minor + other.minor, this.currency);
  }

  minus(other: Money): Money {
    this.assertSameCurrency(other);
    return new Money(this.minor - other.minor, this.currency);
  }

  isGreaterThan(other: Money): boolean {
    this.assertSameCurrency(other);
    return this.minor > other.minor;
  }

  toNumber(): number {
    return this.minor / 10 ** DECIMALS[this.currency];
  }

  format(locale = "en-US"): string {
    return new Intl.NumberFormat(locale, { style: "currency", currency: this.currency }).format(this.toNumber());
  }

  private assertSameCurrency(other: Money): void {
    if (other.currency !== this.currency) {
      throw new TypeError(`Cannot mix ${this.currency} and ${other.currency}`);
    }
  }
}
EOF
put src/shared/money.test.ts <<'EOF'
import { describe, expect, it } from "vitest";
import { Money } from "./money";

describe("Money", () => {
  it("adds without floating-point dust", () => {
    expect(Money.of(0.1, "EUR").plus(Money.of(0.2, "EUR")).minor).toBe(30);
  });

  it("refuses to mix currencies", () => {
    expect(() => Money.of(1, "EUR").plus(Money.of(1, "USD"))).toThrow(TypeError);
  });

  it("formats with the currency symbol", () => {
    expect(Money.of(1234.5, "USD").format()).toBe("$1,234.50");
  });
});
EOF
python3 - src/checkout/payment-service.ts <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace('''export type Currency = "EUR" | "USD" | "GBP";

export interface Money {
  amount: number;
  currency: Currency;
}
''', '''import { Money } from "../shared/money";
''')
open(p, "w").write(s)
PY
commit "Add a Money type that counts in minor units"

# ------------------------------------------------------------------ layer 2: currency conversion
"$REAL_GH" stack add refunds/currency-conversion >/dev/null
put src/shared/exchange-rates.ts <<'EOF'
import { Currency, Money } from "./money";

export interface RateSource {
  rate(from: Currency, to: Currency): Promise<number>;
}

/** [amount] in [to], at today's rate. Rounds once, at the end, to whole minor units. */
export async function convert(amount: Money, to: Currency, rates: RateSource): Promise<Money> {
  if (amount.currency === to) {
    return amount;
  }
  const rate = await rates.rate(amount.currency, to);
  return Money.of(amount.toNumber() * rate, to);
}
EOF
python3 - src/checkout/payment-service.ts <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace('''import { Money } from "../shared/money";
''', '''import { Money } from "../shared/money";
import { convert, RateSource } from "../shared/exchange-rates";
''')
s = s.replace('''  constructor(private readonly gateway: PaymentGateway) {}''',
'''  constructor(
    private readonly gateway: PaymentGateway,
    private readonly rates: RateSource,
  ) {}''')
s = s.replace('''    if (amount.currency !== order.total.currency) {
      throw new PaymentError(order.id, "currency mismatch");
    }
    await this.gateway.refund(order.chargeId, amount);''',
'''    // Refunds go back in the currency the customer paid in.
    const refund = await convert(amount, order.total.currency, this.rates);
    await this.gateway.refund(order.chargeId, refund);''')
open(p, "w").write(s)
PY
commit "Convert refunds to the currency of the order"

# ------------------------------------------------------------------ layer 3: refunds endpoint
"$REAL_GH" stack add refunds/partial-refunds-api >/dev/null
put src/api/refunds.ts <<'EOF'
import { Response, Router } from "./router";
import { Money } from "../shared/money";
import { PaymentError, PaymentService } from "../checkout/payment-service";
import { OrderRepository } from "../orders/order-repository";

const fail = (response: Response, status: number, error: string) =>
  response.status(status).json({ error });

export function registerRefunds(
  router: Router,
  orders: OrderRepository,
  payments: PaymentService,
): void {
  router.post("/orders/:id/refunds", async (request, response) => {
    const order = await orders.find(request.params.id);
    if (!order) {
      return fail(response, 404, "order not found");
    }

    const { amount, reason } = request.body;
    if (typeof amount !== "number" || amount <= 0) {
      return fail(response, 422, "amount must be a positive number");
    }
    const currency = order.total.currency;
    const refund = Money.of(amount, currency);
    const refunded = order.refunded ?? Money.zero(currency);
    const refundable = order.total.minus(refunded);
    if (refund.isGreaterThan(refundable)) {
      return fail(response, 422, `only ${refundable.format()} is left`);
    }

    try {
      await payments.refundPartially(order, refund);
      await orders.save({ ...order, refunded: refunded.plus(refund) });
      return response.status(201).json({ refunded: refund.format() });
    } catch (error) {
      if (error instanceof PaymentError) {
        return fail(response, 502, error.reason);
      }
      throw error;
    }
  });
}
EOF
python3 - src/checkout/payment-service.ts <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace('''  chargeId?: string;
}''', '''  chargeId?: string;
  refunded?: Money;
}''')
open(p, "w").write(s)
PY
commit "Add POST /orders/:id/refunds for partial refunds"

# ------------------------------------------------------------------ layer 4: refund dialog
"$REAL_GH" stack add refunds/refund-dialog >/dev/null
put src/orders/refund-dialog.ts <<'EOF'
import { OrderSummary } from "./orders-page";

export interface RefundRequest {
  orderId: string;
  amount: number;
  reason: string;
}

export function renderRefundDialog(order: OrderSummary): string {
  return `
    <dialog class="refund" data-order="${order.id}">
      <h2>Refund order ${order.id}</h2>
      <label>Amount <input name="amount" type="number" min="0.01" step="0.01" placeholder="${order.total}"></label>
      <label>Reason
        <select name="reason">
          <option>Damaged item</option>
          <option>Late delivery</option>
          <option>Changed their mind</option>
        </select>
      </label>
      <footer><button value="cancel">Cancel</button><button value="refund" class="primary">Refund</button></footer>
    </dialog>`;
}

export async function submitRefund(request: RefundRequest): Promise<string> {
  const response = await fetch(`/orders/${request.orderId}/refunds`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ amount: request.amount, reason: request.reason }),
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.error);
  }
  return body.refunded;
}
EOF
python3 - src/orders/orders-page.ts <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace('''<td>${order.status}</td></tr>`)''',
'''<td>${order.status}</td><td>${refundButton(order)}</td></tr>`)''')
s += '''
function refundButton(order: OrderSummary): string {
  return order.status === "refunded" ? "" : `<button class="link" data-refund="${order.id}">Refund</button>`;
}
'''
open(p, "w").write(s)
PY
commit "Add a refund dialog to the orders page"

# ------------------------------------------------------------------ layer 5: audit log, no PR yet
"$REAL_GH" stack add refunds/audit-log >/dev/null
put src/orders/refund-audit.ts <<'EOF'
import { Money } from "../shared/money";

export interface RefundEntry {
  orderId: string;
  amount: Money;
  reason: string;
  by: string;
  at: Date;
}

/** Every refund, in the order they happened. Finance reconciles against this every night. */
export class RefundAudit {
  private readonly entries: RefundEntry[] = [];

  record(entry: RefundEntry): void {
    this.entries.push(entry);
  }

  forOrder(orderId: string): RefundEntry[] {
    return this.entries.filter((entry) => entry.orderId === orderId);
  }

  total(orderId: string, currency: Money["currency"]): Money {
    return this.forOrder(orderId).reduce((sum, entry) => sum.plus(entry.amount), Money.zero(currency));
  }
}
EOF
commit "Record every refund in an audit log"

# The four published layers are on origin, each tracking its branch; the audit log is local only.
for b in refunds/money-type refunds/currency-conversion refunds/partial-refunds-api refunds/refund-dialog; do
  git push -q -u origin "$b"
done
git fetch -q origin

# A second commit for the currency layer, kept aside (refs/demo, which nothing lists): the capture
# moves the branch to it to show a layer whose upper layers need a rebase. It edits the same line
# of orders-page.ts as the refund dialog layer, so the rebase upstack stops there with a conflict.
base=$(git rev-parse refunds/currency-conversion)
idx=$W/state/index.tmp
GIT_INDEX_FILE=$idx git read-tree "$base"
blob=$(git show "$base:src/orders/orders-page.ts" | python3 -c '
import sys
s = sys.stdin.read()
s = s.replace("<td>${order.total}</td>", "<td class=\"amount\">${order.total}</td>")
sys.stdout.write(s)' | git hash-object -w --stdin)
GIT_INDEX_FILE=$idx git update-index --cacheinfo "100644,$blob,src/orders/orders-page.ts"
tree=$(GIT_INDEX_FILE=$idx git write-tree)
rm -f "$idx"
when="2026-10-17T09:41:00+02:00"
fix=$(GIT_AUTHOR_DATE=$when GIT_COMMITTER_DATE=$when git commit-tree "$tree" -p "$base" -m "Right-align amounts in the orders table")
git update-ref refs/demo/currency-fix "$fix"

git checkout -q refunds/partial-refunds-api
echo "demo repository ready: $REPO"
git log --oneline --graph --all | head -20
