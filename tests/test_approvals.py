from app import approvals


def test_approval_lifecycle():
    item = approvals.create({"action": "launch_app", "app_id": "photoshop"})
    assert item["status"] == "pending"
    assert any(x["id"] == item["id"] for x in approvals.list_pending())
    decided = approvals.decide(item["id"], False)
    assert decided["status"] == "rejected"
    assert approvals.decide(item["id"], True) is None
