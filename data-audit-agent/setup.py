from setuptools import find_packages, setup


setup(
    name="data-audit-agent",
    version="0.1.0",
    description="Optional LangGraph sidecar for DataAudit artifact-contract workflows.",
    packages=find_packages(),
    python_requires=">=3.6",
    extras_require={
        "langgraph": ["langgraph>=0.2"],
    },
    entry_points={
        "console_scripts": [
            "data-audit-agent=data_audit_agent.demo:main",
        ],
    },
)
