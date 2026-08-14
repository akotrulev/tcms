/**
 * A `fetch` stub that records what was sent and answers what the test says.
 *
 * Deliberately a real `Response` rather than a duck-typed object: `http.ts` reads `ok`,
 * `status`, `statusText`, `headers` and `json()`, and a hand-rolled stand-in is a second
 * implementation of the parts of the Fetch spec this code depends on — which agrees with the
 * real one until the day the code starts reading a sixth member.
 *
 * Since §203 every exchange it serves is compared against `openapi.yaml` on the way past — see
 * `contractExchange.ts`, which is where the reason and the escape hatch live. A great many of this
 * package's own tests take that hatch, and legitimately: the transport's behaviour against a body the
 * contract cannot describe is most of what `http.test.ts` is for.
 */
import { checkExchange } from './contractExchange.js';

export interface RecordedRequest {
  url: string;
  method: string;
  headers: Headers;
  body: string | null;
}

export interface StubFetch {
  fetch: typeof globalThis.fetch;
  readonly calls: RecordedRequest[];
  readonly last: RecordedRequest;
}

type Answer =
  | { status: number; body?: unknown; headers?: Record<string, string>; text?: string }
  | { throws: unknown }
  | { hangs: true };

export function stubFetch(...answers: Answer[]): StubFetch {
  const calls: RecordedRequest[] = [];
  let index = 0;

  const fetch: typeof globalThis.fetch = async (input, init) => {
    // The transport sends a `Request`; a direct caller may send a URL and an init. Both are
    // recorded through the same normalization, so a test never has to know which it produced.
    const request = input instanceof Request ? input : new Request(input, init);
    const recorded: RecordedRequest = {
      url: request.url,
      method: request.method,
      headers: new Headers(request.headers),
      body: request.body ? await request.text() : null,
    };
    calls.push(recorded);

    const answer = answers[Math.min(index++, answers.length - 1)] ?? { status: 200, body: {} };

    /*
      Compared before the two non-answering branches below, so a test that drives a hang or a
      transport throw still has the request it sent checked. There is no response to check in
      either case, which is what `status: 0` says: `checkExchange` compares a served body only for
      a 2xx, so a zero is the honest way to spell "this exchange never produced one".

      An `answer.text` is likewise not compared: `text` exists in this stub precisely so a test can
      serve something that is not a JSON body of the declared shape — a truncated payload, an HTML
      error page — and comparing it against the success schema would report the subject of the test
      as a defect in it.
    */
    checkExchange({
      method: recorded.method,
      pathname: new URL(recorded.url).pathname,
      requestBody: recorded.body,
      status: 'status' in answer ? answer.status : 0,
      responseBody: 'body' in answer ? answer.body : undefined,
    });

    if ('throws' in answer) throw answer.throws;

    if ('hangs' in answer) {
      /*
        Never resolves on its own; only the caller's signal ends it — which is what a hung server
        looks like from here.

        It listens on `init.signal`, **not** on `request.signal`, and that is the whole reason
        this branch is worth a comment: a signal read back off a `Request` does not reliably
        re-dispatch its abort event — `new Request(url, { signal }).signal` never fires at all on
        Node 22.12 — so a stub reading the derived one hangs while the caller's deadline fires
        exactly on time. Same trap `http.ts` records at its `doFetch` call, met from the other
        side, which is why neither place relies on the derived signal.
      */
      const signal = init?.signal ?? request.signal;
      return new Promise<Response>((_resolve, reject) => {
        if (signal.aborted) reject(signal.reason as Error);
        signal.addEventListener('abort', () => {
          reject((signal.reason as Error) ?? new DOMException('aborted', 'AbortError'));
        });
      });
    }

    const headers = new Headers(answer.headers);
    const hasBody = answer.status !== 204 && (answer.body !== undefined || answer.text !== undefined);
    if (hasBody && !headers.has('content-type')) headers.set('content-type', 'application/json');

    return new Response(
      answer.status === 204 ? null : (answer.text ?? JSON.stringify(answer.body ?? {})),
      { status: answer.status, headers },
    );
  };

  return {
    fetch,
    calls,
    get last() {
      const call = calls.at(-1);
      if (!call) throw new Error('No request was made.');
      return call;
    },
  };
}
