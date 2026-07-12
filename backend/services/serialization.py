def serialize(obj):
    return {c.name: getattr(obj, c.name) for c in obj.__table__.columns}

def amount(value:float) -> float:
    return round(float(value or 0),2)
