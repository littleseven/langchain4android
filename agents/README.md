# PicMe AI Agent System

## 🎭 Available Agents

This project uses a multi-agent collaboration system for AI-powered software development.

### Core Agents

| Agent | File | Role | Keywords |
|-------|------|------|----------|
| **Product Agent** | `pm_agent.md` | Product Manager (PM) | requirements, UX, features, user value |
| **Engineering Agent** | `rd_agent.md` | Software Engineer (RD) | implementation, architecture, code quality |
| **Review Agent** | `review_agent.md` | Code Reviewer | code review, quality assurance, best practices |
| **Testing Agent** | `qa_agent.md` | QA Engineer | testing strategy, test cases, bug prevention |

## 🚀 Quick Start

### Activate Specific Agent
Prefix your question with the agent name:

```
[Product] What's the user value of this feature?
[Engineering] How to implement duplicate detection?
[Review] Please review this code
[Testing] What test cases should I write?
```

### Automatic Agent Selection
Just describe your task, and the system will activate the appropriate agent:

```
"I want to add a photo compression feature"
→ Product Agent analyzes requirements
→ Engineering Agent proposes solutions  
→ Testing Agent designs test cases
→ Review Agent validates code quality
```

## 📁 File Structure

```
PicMe/
├── agents/                    # Agent configuration files
│   ├── README.md             # This file - system overview
│   ├── product_agent.md      # Product Manager role
│   ├── engineering_agent.md  # Software Engineer role
│   ├── review_agent.md       # Code Reviewer role
│   └── testing_agent.md      # QA Engineer role
├── .agent/                   # Legacy agent folder (deprecated)
└── ...                       # Project source code
```

## 🔄 Collaboration Workflows

### Feature Development Flow
```
User Request → Product Agent (Requirements)
            → Engineering Agent (Implementation)
            → Testing Agent (Quality Assurance)
            → Review Agent (Code Review)
            → User (Delivery)
```

### Bug Fix Flow
```
Bug Report → Testing Agent (Analysis)
          → Engineering Agent (Fix)
          → Review Agent (Validation)
          → User (Confirmation)
```

## 💡 Best Practices

### DO ✅
- Be specific about your requirements
- Provide context when asking questions
- Review agent suggestions critically
- Combine multiple agents for complex tasks

### DON'T ❌
- Skip requirement analysis
- Ignore review comments
- Merge without testing
- Accept vague answers

## 🎯 Agent Responsibilities

### Product Agent
- Requirement analysis and clarification
- User experience design
- Feature prioritization
- Success metrics definition

### Engineering Agent
- Technical solution design
- Clean Architecture implementation
- Code quality and performance
- Best practices application

### Review Agent
- Code quality scoring
- Architecture compliance check
- Performance optimization suggestions
- Security and risk assessment

### Testing Agent
- Test strategy design
- Test case creation
- Bug severity classification
- Quality metrics tracking

## 📊 Quality Standards

All agents follow these principles:

1. **Clean Architecture**: Strict separation of Domain/Data/Presentation layers
2. **Type Safety**: Kotlin best practices, sealed classes, data classes
3. **Performance**: All operations < 100ms response time
4. **Testing**: Core logic must have unit tests (≥80% coverage)
5. **Documentation**: Clear comments, KDoc format

## 🔧 For AI Assistants

When assisting with this project:

1. **Understand the role**: Check which agent is being activated
2. **Follow the guidelines**: Each agent has specific responsibilities
3. **Maintain consistency**: Use established patterns and conventions
4. **Collaborate**: Work with other agents when needed
5. **Quality first**: Never compromise on code quality or user experience

## 📚 Documentation

- **System Overview**: `agents/README.md` (this file)
- **Usage Examples**: `agents/USAGE_EXAMPLES.md`
- **Role Details**: See individual agent files

## 🌟 Example Interactions

### Example 1: New Feature
```
User: "I want to add smart album categorization"

[Product Agent activates]
Product: "Let me understand the requirements..."
- Target users?
- Categorization dimensions?
- Expected value?

[Engineering Agent joins]
Engineering: "Based on the PRD, I suggest..."
- ML Kit for image labeling
- Background service for processing
- Estimated: 16 hours

[Testing Agent contributes]
Testing: "Test strategy includes..."
- Accuracy testing
- Performance testing
- Edge cases

[Review Agent validates]
Review: "Architecture looks good, score: 90/100"
```

### Example 2: Technical Question
```
User: "[Engineering] How to handle async image loading?"

Engineering: "In PicMe, we use Coil with Compose..."
- Automatic memory management
- Lifecycle-aware
- Built-in caching

[Review Agent adds]
Review: "Don't forget..."
- Add placeholder images
- Handle error states
- Consider cross-thread calls
```

---

**Remember**: Great teams use great tools. Multi-agent collaboration makes development faster and more reliable!
