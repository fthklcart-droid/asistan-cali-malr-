from types import SimpleNamespace as NS

from app.computer_agent import NOT_EXECUTED, describe_action, needs_approval, run_agent


def tool(id_, name, **inp):
    return NS(type="tool_use", id=id_, name=name, input=inp, toolset_name=None if name == "ask_user" else "computer")


def text(t):
    return NS(type="text", text=t)


class FakeClient:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []
        self.beta = NS(messages=NS(create=self.create))

    def create(self, **kwargs):
        self.calls.append({**kwargs, "messages": list(kwargs["messages"])})
        return self.responses.pop(0)


class FakeExecutor:
    def __init__(self, fail_on=None):
        self.ran = []
        self.fail_on = fail_on

    def run(self, name, args):
        if name == self.fail_on:
            raise RuntimeError("tıklanamadı")
        self.ran.append(name)
        return {"image": "AAAA"} if name == "screenshot" else {"text": "OK"}


class CB:
    def __init__(self, decision="yes", answer="evet"):
        self.decision, self.answer = decision, answer
        self.logs, self.approvals, self.questions = [], [], []
        self.stop = False

    def log(self, t): self.logs.append(t)
    def approve(self, actions): self.approvals.append(actions); return self.decision
    def ask_user(self, q): self.questions.append(q); return self.answer
    def should_stop(self): return self.stop


def resp(content, stop="tool_use"):
    return NS(content=content, stop_reason=stop)


def test_full_loop_echoes_toolset_name_and_finishes():
    client = FakeClient([
        resp([text("Bakıyorum"), tool("t1", "screenshot"), tool("t2", "left_click", coordinate=[10, 20])]),
        resp([text("Bitti: Chrome açıldı.")], stop="end_turn"),
    ])
    ex, cb = FakeExecutor(), CB()
    result = run_agent("Chrome'u aç", ex, cb, "every", client=client, sleep=lambda s: None)
    assert result.status == "done" and "Chrome" in result.summary
    assert ex.ran == ["screenshot", "left_click"]
    assert len(cb.approvals) == 1 and cb.approvals[0] == [("left_click", {"coordinate": [10, 20]})]
    sent = client.calls[1]["messages"][-1]["content"]
    assert all(r["toolset_name"] == "computer" for r in sent)
    assert sent[0]["content"][0]["type"] == "image"
    assert client.calls[0]["tools"][0] == {"type": "computer_toolset_20260801"}


def test_rejection_is_reported_and_rest_not_executed():
    client = FakeClient([
        resp([tool("t1", "type", text="merhaba"), tool("t2", "key", text="Return")]),
        resp([text("Tamam, yapmadım.")], stop="end_turn"),
    ])
    ex = FakeExecutor()
    run_agent("yaz", ex, CB(decision="no"), "every", client=client, sleep=lambda s: None)
    assert ex.ran == []
    results = client.calls[1]["messages"][-1]["content"]
    assert results[0]["is_error"] and results[1]["content"] == NOT_EXECUTED


def test_failure_marks_remaining_actions_not_executed():
    client = FakeClient([
        resp([tool("t1", "left_click", coordinate=[1, 1]), tool("t2", "screenshot")]),
        resp([text("Sorun var.")], stop="end_turn"),
    ])
    run_agent("x", FakeExecutor(fail_on="left_click"), CB(), "auto", client=client, sleep=lambda s: None)
    results = client.calls[1]["messages"][-1]["content"]
    assert results[0]["is_error"] and results[1]["content"] == NOT_EXECUTED


def test_ask_user_has_no_toolset_name_and_stop_works():
    client = FakeClient([resp([tool("q1", "ask_user", question="Göndereyim mi?")]),
                         resp([text("ok")], stop="end_turn")])
    cb = CB(answer="hayır")
    run_agent("x", FakeExecutor(), cb, "every", client=client, sleep=lambda s: None)
    r = client.calls[1]["messages"][-1]["content"][0]
    assert "toolset_name" not in r and r["content"] == "hayır"
    assert cb.questions == ["Göndereyim mi?"]

    cb2 = CB(decision="stop")
    client2 = FakeClient([resp([tool("t1", "left_click", coordinate=[1, 1])])])
    assert run_agent("x", FakeExecutor(), cb2, "every", client=client2, sleep=lambda s: None).status == "stopped"


def test_refusal_and_approval_modes():
    client = FakeClient([resp([], stop="refusal")])
    assert run_agent("x", FakeExecutor(), CB(), client=client).status == "refused"
    assert not needs_approval("screenshot", "every")
    assert needs_approval("left_click", "every")
    assert not needs_approval("left_click", "sensitive") and needs_approval("type", "sensitive")
    assert not needs_approval("type", "auto")
    assert "Yaz" in describe_action("type", {"text": "çay"})
